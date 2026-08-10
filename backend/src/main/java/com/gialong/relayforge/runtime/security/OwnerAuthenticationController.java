package com.gialong.relayforge.runtime.security;

import com.gialong.relayforge.identity.api.OwnerLoginNames;
import com.gialong.relayforge.identity.api.VerifiedOwner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class OwnerAuthenticationController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final OwnerLoginFailureLimiter failureLimiter;
    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    OwnerAuthenticationController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            OwnerLoginFailureLimiter failureLimiter
    ) {
        this.authenticationManager = Objects.requireNonNull(authenticationManager, "authenticationManager must not be null");
        this.securityContextRepository = Objects.requireNonNull(
                securityContextRepository,
                "securityContextRepository must not be null"
        );
        this.sessionAuthenticationStrategy = Objects.requireNonNull(
                sessionAuthenticationStrategy,
                "sessionAuthenticationStrategy must not be null"
        );
        this.failureLimiter = Objects.requireNonNull(failureLimiter, "failureLimiter must not be null");
    }

    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getToken());
    }

    @PostMapping("/session")
    ResponseEntity<OwnerResponse> login(
            @RequestBody OwnerLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        String limiterLogin = OwnerLoginNames.canonicalize(request.loginName()).orElse("");
        String sourceIp = servletRequest.getRemoteAddr();
        if (failureLimiter.isRateLimited(limiterLogin, sourceIp)) {
            throw new OwnerLoginRateLimitExceededException();
        }

        try {
            Authentication authenticated = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.loginName(), request.password())
            );
            sessionAuthenticationStrategy.onAuthentication(authenticated, servletRequest, servletResponse);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authenticated);
            securityContextRepository.saveContext(context, servletRequest, servletResponse);
            failureLimiter.clear(limiterLogin, sourceIp);

            VerifiedOwner owner = verifiedOwner(authenticated);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new OwnerResponse(owner.ownerId().toString(), owner.loginName()));
        } catch (BadCredentialsException exception) {
            failureLimiter.recordFailure(limiterLogin, sourceIp);
            throw exception;
        }
    }

    @GetMapping("/me")
    ResponseEntity<OwnerResponse> currentOwner(Authentication authentication) {
        VerifiedOwner owner = verifiedOwner(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new OwnerResponse(owner.ownerId().toString(), owner.loginName()));
    }

    @DeleteMapping("/session")
    ResponseEntity<Void> logout(
            Authentication authentication,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        logoutHandler.logout(servletRequest, servletResponse, authentication);
        return ResponseEntity.noContent().build();
    }

    private static VerifiedOwner verifiedOwner(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof VerifiedOwner owner)) {
            throw new BadCredentialsException("Invalid owner credentials");
        }
        return owner;
    }

    record OwnerLoginRequest(String loginName, String password) {
    }

    record OwnerResponse(String ownerId, String loginName) {
    }

    record CsrfResponse(String headerName, String token) {
    }
}
