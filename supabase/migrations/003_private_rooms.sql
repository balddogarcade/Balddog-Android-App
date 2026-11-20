-- Agrega soporte para salas privadas (ocultas del listado público)
alter table public.rooms
    add column if not exists is_private boolean not null default false;

create index if not exists idx_rooms_status_private
    on public.rooms(status, is_private);
