-- NEIS database schema V16 (US-06.9, issue #88).
-- DOM analysis digest: the change digest of the pruned element tree
-- (Skyvern-inspired, Java re-implementation) per capture — a SHA-256
-- hex over the canonical tree rendering, stored with the webpage
-- capture metadata (US-11.4) so unchanged pages are detected between
-- runs. NULL = the capture predates the DOM analysis (and a
-- digest-less re-capture never wipes a stored digest, mirroring the
-- filters/clearing rules of V11/V15).
--
-- Migration rules (US-02.3): an applied migration file is NEVER edited;
-- every schema change ships as a new Vn file in this directory.

ALTER TABLE captures ADD COLUMN dom_digest TEXT;
