from pathlib import Path
import tarfile

archive = Path(".merge/AI-Fixes.tar.xz")
extract_dir = Path(".merge/extracted")
expected = {
    "NeoplasmDeathHeatmap.java",
    "NeoplasmGroundAI.java",
    "NeoplasmFlyingAI.java",
    "PathfinderExtended.java",
    "NeoplasmAIController.java",
    "NeoplasmStrategicPathfinder.java",
    "PathfinderCustom.java",
}

extract_dir.mkdir(parents=True, exist_ok=True)
with tarfile.open(archive, "r:xz") as tf:
    root = extract_dir.resolve()
    for member in tf.getmembers():
        if not member.isfile():
            continue
        target = (extract_dir / member.name).resolve()
        if not target.is_relative_to(root):
            raise SystemExit("Unsafe path in source archive: " + member.name)
        target.parent.mkdir(parents=True, exist_ok=True)
        source = tf.extractfile(member)
        if source is None:
            raise SystemExit("Cannot read archive member: " + member.name)
        target.write_bytes(source.read())

source_dir = extract_dir / "src/classicMod/library/ai"
actual = {p.name for p in source_dir.glob("*.java")}
if actual != expected:
    raise SystemExit("Unexpected AI source set: " + repr(sorted(actual)))

for name in sorted(expected):
    target = Path("src/classicMod/library/ai") / name
    data = (source_dir / name).read_text(encoding="utf-8")

    if name == "NeoplasmAIController.java":
        old = "return Math.max(0f, 9f - age * 0.35f);"
        if old not in data:
            raise SystemExit("Expected low anti-hover penalty not found.")
        data = data.replace(old, "return 28f - age * 0.8f;", 1)
        if "revisitPenalty(best) < 7f" not in data or "revisitPenalty(best) >= 7f" not in data:
            raise SystemExit("Expected escape thresholds not found.")
        data = data.replace("revisitPenalty(best) < 7f", "revisitPenalty(best) < 20f", 1)
        data = data.replace("revisitPenalty(best) >= 7f", "revisitPenalty(best) >= 20f", 1)
        if "activeDecisionTick == tick) return true;" not in data:
            raise SystemExit("Shared decision-tick guard is missing.")

    if name == "NeoplasmStrategicPathfinder.java":
        old = "boolean flanksOrAdvances = distance <= currentDistance + 1.05f;"
        if old not in data:
            raise SystemExit("Expected relaxed fallback detour rule not found.")
        data = data.replace(old, "boolean advances = distance + 0.01f < currentDistance;", 1)
        old2 = "if (!flanksOrAdvances && !escapes) continue;"
        if old2 not in data:
            raise SystemExit("Expected fallback detour guard not found.")
        data = data.replace(old2, "if (!advances && !escapes) continue;", 1)

    target.write_text(data, encoding="utf-8")
    print("Merged", target)

# Guard against replacing unrelated content or population balance.
mod = Path("src/classicMod/ClassicMod.java").read_text(encoding="utf-8")
blocks = Path("src/classicMod/content/RBlocks.java").read_text(encoding="utf-8")
population = Path("src/classicMod/library/blocks/neoplasiaBlocks/NeoplasmPopulation.java").read_text(encoding="utf-8")
growth = Path("src/classicMod/library/blocks/neoplasiaBlocks/NeoplasmGrowthLimiter.java").read_text(encoding="utf-8")
assert "if (!unit.team.isAI()) return controller;" in mod
assert "maxTotal = 32" in population
assert "maxBlobs = 8" in population
assert "maxSiegeCrawlers = 3" in population
assert "maxSporeCarriers = 2" in population
assert "expansionInterval = 45f" in growth
assert "spawnTime = 240f" in blocks and "spawnTime = 210f" in blocks
print("Verified preserved player AI restriction, content, and anti-snowball limits.")
