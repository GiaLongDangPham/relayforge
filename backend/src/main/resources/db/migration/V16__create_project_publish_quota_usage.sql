create table public.project_publish_quota_usage (
    project_id uuid primary key references public.projects (id) on delete restrict,
    quota_day date not null,
    accepted_event_count integer not null,
    constraint ck_project_publish_quota_usage_positive_count
        check (accepted_event_count > 0)
);
