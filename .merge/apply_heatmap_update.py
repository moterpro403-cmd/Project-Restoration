from pathlib import Path
import zipfile

archive = Path('.merge/Neoplasm-Heatmap-Routing-Update.zip')
expected = {
    'src/classicMod/library/ai/NeoplasmDeathHeatmap.java',
    'src/classicMod/library/ai/NeoplasmStrategicPathfinder.java',
    'src/classicMod/library/ai/NeoplasmFlyingAI.java',
    'src/classicMod/library/ai/NeoplasmAIController.java',
    'src/classicMod/library/ai/NeoplasmGroundAI.java',
}
if not archive.is_file():
    raise SystemExit(f'Missing update archive: {archive}')
with zipfile.ZipFile(archive) as zf:
    bad = zf.testzip()
    if bad:
        raise SystemExit(f'Corrupt archive entry: {bad}')
    names = {n for n in zf.namelist() if not n.endswith('/')}
    if names != expected:
        raise SystemExit(f'Unexpected archive contents. Missing={expected - names}, extra={names - expected}')
    for name in sorted(expected):
        target = Path(name)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(zf.read(name))

controller = Path('src/classicMod/library/ai/NeoplasmAIController.java').read_text()
assert 'return 28f - age * 0.8f;' in controller, 'Strong anti-hover revisit penalty missing'
assert 'revisitPenalty(best) < 20f' in controller, 'Anti-hover escape threshold missing'
assert 'private int riskInterval = 5;' in controller, 'Mobile AI decision cadence missing'
assert '? 10' in controller and '? 5 : 2;' in controller, 'Expected 10/5/2 tick schedule missing'

heatmap = Path('src/classicMod/library/ai/NeoplasmDeathHeatmap.java').read_text()
assert 'final float[] updated;' in heatmap, 'Heatmap decay timestamp type mismatch'
assert 'decayStepTicks' in heatmap and 'halfLifeTicks' in heatmap, 'Heatmap decay constants missing'

pathfinder = Path('src/classicMod/library/ai/NeoplasmStrategicPathfinder.java').read_text()
assert 'trafficRefreshTicks = 120f;' in pathfinder, 'Android-safe traffic scan interval missing'
assert 'maxCachedRoutes = 12;' in pathfinder, 'Android-safe route cache cap missing'

cord = Path('src/classicMod/library/blocks/neoplasiaBlocks/CausticCord.java').read_text()
assert 'cordGrowthInterval = 60f' in cord and 'nextCordGrowthTime' in cord, 'Independent 60-tick cord timer was lost'
print('Applied 5 AI files; anti-hover, mobile cadence, and independent 60-tick cord checks passed.')
