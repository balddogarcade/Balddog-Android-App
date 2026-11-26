-- 034_enrich_games_metadata.sql
-- Expande public.games con metadatos completos de Arcade Database (adb.arcadeitalia.net)
-- y referencias IPFS para sus imágenes, manteniendo intactos cover_url y title actuales.

ALTER TABLE public.games
  ADD COLUMN IF NOT EXISTS game_name TEXT,
  ADD COLUMN IF NOT EXISTS adb_title TEXT,
  ADD COLUMN IF NOT EXISTS short_title TEXT,
  ADD COLUMN IF NOT EXISTS manufacturer TEXT,
  ADD COLUMN IF NOT EXISTS year TEXT,
  ADD COLUMN IF NOT EXISTS genre TEXT,
  ADD COLUMN IF NOT EXISTS serie TEXT,
  ADD COLUMN IF NOT EXISTS players INTEGER,
  ADD COLUMN IF NOT EXISTS nplayers TEXT,
  ADD COLUMN IF NOT EXISTS emulator_name TEXT,
  ADD COLUMN IF NOT EXISTS input_controls TEXT,
  ADD COLUMN IF NOT EXISTS input_buttons INTEGER,
  ADD COLUMN IF NOT EXISTS buttons_colors TEXT,
  ADD COLUMN IF NOT EXISTS history TEXT,
  ADD COLUMN IF NOT EXISTS history_copyright_long TEXT,
  ADD COLUMN IF NOT EXISTS youtube_video_id TEXT,
  ADD COLUMN IF NOT EXISTS url_image_ingame TEXT,
  ADD COLUMN IF NOT EXISTS url_image_title TEXT,
  ADD COLUMN IF NOT EXISTS url_image_marquee TEXT,
  ADD COLUMN IF NOT EXISTS url_image_cabinet TEXT,
  ADD COLUMN IF NOT EXISTS url_image_flyer TEXT,
  ADD COLUMN IF NOT EXISTS url_icon TEXT;
