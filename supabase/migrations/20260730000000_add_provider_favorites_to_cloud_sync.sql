alter table public.user_media_state
    drop constraint if exists user_media_state_media_type_check;

alter table public.user_media_state
    add constraint user_media_state_media_type_check
    check (media_type in ('movie', 'tv_show', 'episode', 'provider_favorite'));
