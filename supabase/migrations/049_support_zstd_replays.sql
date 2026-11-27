-- Fase 8: Soporte dual para replays .bsv.zst (Zstandard) y .bsv.gz (Gzip legacy) en Storage RLS

drop policy if exists "replays_public_read" on storage.objects;
create policy "replays_public_read"
on storage.objects
for select
to public
using (
  bucket_id = 'replays'
  and position('..' in name) = 0
  and name ~ '\.bsv\.(gz|zst)$'
);

drop policy if exists "replays_owner_insert" on storage.objects;
create policy "replays_owner_insert"
on storage.objects
for insert
to authenticated
with check (
  bucket_id = 'replays'
  and position('..' in name) = 0
  and name ~ ('^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/' || auth.uid()::text || '/[A-Za-z0-9_-]+\.bsv\.(gz|zst)$')
);

drop policy if exists "replays_owner_delete" on storage.objects;
create policy "replays_owner_delete"
on storage.objects
for delete
to authenticated
using (
  bucket_id = 'replays'
  and position('..' in name) = 0
  and (
    name ~ ('^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/' || auth.uid()::text || '/[A-Za-z0-9_-]+\.bsv\.(gz|zst)$')
    or name ~ ('/' || auth.uid()::text || '\.bsv\.(gz|zst)$')
  )
  and not exists (
    select 1
    from public.high_scores hs
    where hs.replay_path = objects.name
      and hs.verification_status = any (array['pending'::text, 'verifying'::text, 'verified'::text])
  )
);
