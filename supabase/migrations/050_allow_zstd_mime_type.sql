-- Allow Zstandard (.bsv.zst) MIME type in replays Storage bucket
update storage.buckets
set allowed_mime_types = array[
    'application/gzip',
    'application/x-gzip',
    'application/zstd',
    'application/octet-stream'
]
where id = 'replays';
