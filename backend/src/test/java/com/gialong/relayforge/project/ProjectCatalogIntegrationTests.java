package com.gialong.relayforge.project;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import com.gialong.relayforge.project.api.ProjectCatalog;
import com.gialong.relayforge.project.api.ProjectDetails;
import com.gialong.relayforge.project.api.ProjectPage;
import com.gialong.relayforge.project.api.ProjectVersionConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(
        classes = RelayForgeApplication.class,
        properties = "relayforge.runtime=api",
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
class ProjectCatalogIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_project_test")
            .withUsername("relayforge_project_test")
            .withPassword("relayforge_project_test");

    @Autowired
    private OwnerBootstrap ownerBootstrap;

    @Autowired
    private ProjectCatalog projectCatalog;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsAndQueriesOnlyTheSuppliedOwnersProjects() {
        UUID firstOwnerId = bootstrap("project.first.owner").ownerId();
        UUID secondOwnerId = bootstrap("project.second.owner").ownerId();

        ProjectDetails firstProject = projectCatalog.create(firstOwnerId, " Payments ");
        ProjectDetails duplicateNameProject = projectCatalog.create(firstOwnerId, "Payments");
        ProjectDetails otherOwnersProject = projectCatalog.create(secondOwnerId, "Operations");

        assertThat(firstProject.name()).isEqualTo("Payments");
        assertThat(firstProject.version()).isZero();
        assertThat(firstProject.createdAt()).isNotNull();
        assertThat(firstProject.updatedAt()).isEqualTo(firstProject.createdAt());
        assertThat(jdbcTemplate.queryForObject(
                "select owner_id from projects where id = ?", UUID.class, firstProject.id()
        )).isEqualTo(firstOwnerId);
        assertThat(projectCatalog.findOwned(firstOwnerId, firstProject.id())).contains(firstProject);
        assertThat(projectCatalog.findOwned(secondOwnerId, firstProject.id())).isEmpty();

        List<ProjectDetails> firstOwnerProjects = projectCatalog.listOwned(firstOwnerId, 100, null).items();
        assertThat(firstOwnerProjects).containsExactlyInAnyOrder(firstProject, duplicateNameProject);
        assertThat(firstOwnerProjects).isSortedAccordingTo(newestFirst());
        assertThat(projectCatalog.listOwned(secondOwnerId, 100, null).items()).containsExactly(otherOwnersProject);

        ProjectPage firstPage = projectCatalog.listOwned(firstOwnerId, 1, null);
        ProjectPage secondPage = projectCatalog.listOwned(firstOwnerId, 1, firstPage.nextCursor());
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.nextCursor()).isNull();
        assertThat(firstPage.items()).extracting(ProjectDetails::id)
                .doesNotContainAnyElementsOf(secondPage.items().stream().map(ProjectDetails::id).toList());
    }

    @Test
    void renamesOnlyTheCurrentOwnersProjectAndRejectsStaleVersion() {
        UUID ownerId = bootstrap("project.rename.owner").ownerId();
        UUID otherOwnerId = bootstrap("project.rename.other").ownerId();
        ProjectDetails created = projectCatalog.create(ownerId, "Before rename");

        ProjectDetails renamed = projectCatalog.rename(ownerId, created.id(), " After rename ", 0).orElseThrow();

        assertThat(renamed.name()).isEqualTo("After rename");
        assertThat(renamed.version()).isEqualTo(1);
        assertThat(projectCatalog.rename(otherOwnerId, created.id(), "Not allowed", 1)).isEmpty();
        assertThatThrownBy(() -> projectCatalog.rename(ownerId, created.id(), "Stale", 0))
                .isInstanceOf(ProjectVersionConflictException.class);
        assertThat(projectCatalog.findOwned(ownerId, created.id())).contains(renamed);
    }

    @Test
    void rejectsInvalidNamesBeforePersistingAProject() {
        UUID ownerId = bootstrap("project.validation.owner").ownerId();

        assertThatThrownBy(() -> projectCatalog.create(ownerId, " \t\r\n "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projectCatalog.create(ownerId, "p".repeat(121)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projectCatalog.create(null, "Valid name"))
                .isInstanceOf(NullPointerException.class);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from projects where owner_id = ?", Integer.class, ownerId
        )).isZero();
    }

    private OwnerBootstrapResult bootstrap(String loginName) {
        char[] password = "project-catalog-test-password".toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static Comparator<ProjectDetails> newestFirst() {
        return Comparator.comparing(ProjectDetails::createdAt, Comparator.reverseOrder())
                .thenComparing(ProjectDetails::id, Comparator.reverseOrder());
    }
}
