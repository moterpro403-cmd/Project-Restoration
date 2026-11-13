# Phase 3 placeholder-art guide

The first two Phase 3 prototypes deliberately reuse existing Neoplasm artwork so gameplay can be balanced before custom pixel art is commissioned or generated.

## Temporary aliases

- `siege-crawler.png` and `siege-crawler-full.png`: copied from the existing worm body artwork.
- `siege-crawler-segment0.png` through `segment2.png`: copied from the existing worm segment frames.
- `spore-carrier.png`: copied from the existing matte unit sprite.
- `siege-crawler-spawner.png`: copied from the existing mule spawner.
- `spore-carrier-spawner.png`: copied from the existing hydro pod.

## Replacement contract

Keep the filenames and transparent PNG format unchanged when replacing these placeholders. Match the dimensions of the corresponding source sprite so the existing hitboxes, unit scale, segment offsets, and building footprints remain valid. Keep the Neoplasm palette focused on dark violet, magenta, and muted organic highlights, with a clear silhouette at Mindustry's normal zoom.

Gameplay code must not depend on the placeholder artwork. Replacing an image at one of these paths should not require a Java code change.
