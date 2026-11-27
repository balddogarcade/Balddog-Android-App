-- Migración 046: Crea el bucket público 'covers' en Supabase Storage y actualiza
-- public.games.cover_url para servir las portadas .webp desde Supabase Storage CDN.
-- Las ROMs (ipfs_cid) y las imágenes secundarias de detalle permanecen en IPFS.

-- 1. Bucket público de lectura para portadas (límite 1 MB por imagen)
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'covers',
    'covers',
    true,
    1048576,
    array['image/webp', 'image/png', 'image/jpeg']
)
on conflict (id) do update
set public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "covers_public_read" on storage.objects;
create policy "covers_public_read"
on storage.objects for select
to anon, authenticated
using (bucket_id = 'covers');

-- 2. Actualizar cover_url en public.games al bucket público 'covers'
update public.games
set cover_url = 'https://ypsienauihfrzesknwvt.supabase.co/storage/v1/object/public/covers/' || slug || '.webp'
where slug in (
    'altbeast',
    'btoads',
    'captcomm',
    'dbz2',
    'ddragon2',
    'ddtod',
    'dino',
    'ffight',
    'ghouls',
    'knights',
    'kod',
    'mslug',
    'mslug3',
    'nbajam',
    'nbbatman',
    'pbobble',
    'powerins',
    'punisher',
    'robocop',
    'simpsons',
    'snowbro2',
    'snowbros',
    'spidman',
    'ssriders',
    'tmnt',
    'tmnt2',
    'tumblep',
    'uccops',
    'viostorm',
    'wwfwfestu'
);
