-- The Avatars bucket is public, but listing its objects still requires an RLS policy.
-- This lets signed-out and signed-in app users discover the available profile images.
create policy "Public avatars are listable"
on storage.objects
for select
to anon, authenticated
using (bucket_id = 'Avatars');
