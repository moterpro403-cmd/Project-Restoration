from pathlib import Path

ROOT = Path('src/themass')


def replace(path: str, old: str, new: str) -> None:
    file = ROOT / path
    data = file.read_text(encoding='utf-8')
    if new in data:
        return
    if old not in data:
        raise SystemExit(f'Patch anchor not found in {file}: {old!r}')
    file.write_text(data.replace(old, new), encoding='utf-8')


# Mindustry v160.7 Block.health is an int, not a float.
for path, old, new in [
    ('blocks/NeoplasmHeart.java', 'health = 4600f;', 'health = 4600;'),
    ('blocks/NeoplasmVein.java', 'health = plated ? 1450f : 430f;', 'health = plated ? 1450 : 430;'),
    ('blocks/HeavyArteryBridge.java', 'health = 8500f;', 'health = 8500;'),
    ('blocks/NeoplasmDrill.java', 'health = 850f;', 'health = 850;'),
    ('blocks/NeoplasmPod.java', 'health = kind == Kind.heavy ? 1150f : 560f;', 'health = kind == Kind.heavy ? 1150 : 560;'),
    ('blocks/NeoplasmTurrets.java', 'health = kind == Kind.scathe ? 6900f : 780f;', 'health = kind == Kind.scathe ? 6900 : 780;'),
    ('blocks/NeoplasmTurrets.java', 'health=380f;', 'health=380;'),
    ('blocks/NeoplasmTraps.java', 'health=150f;', 'health=150;'),
    ('blocks/NeoplasmTraps.java', 'health=420f;', 'health=420;'),
]:
    replace(path, old, new)

# Correct v160.7 API packages.
replace('blocks/NeoplasmVein.java',
        'import mindustry.content.Items;\n',
        'import mindustry.content.Items;\nimport mindustry.type.Item;\n')
replace('NeoplasmUnits.java',
        'import mindustry.entities.units.Weapon;\n',
        'import mindustry.type.Weapon;\n')
replace('NeoplasmUnits.java',
        'mindustry.content.Sounds.shoot',
        'mindustry.gen.Sounds.shoot')
replace('NeoplasmMod.java',
        'import mindustry.entities.Groups;\n',
        'import mindustry.gen.Groups;\n')
replace('abilities/CarrierBlobDropAbility.java',
        'mindustry.entities.Groups.unit',
        'mindustry.gen.Groups.unit')

# Insert host lifecycle bridges at the loadContent method anchor; this remains
# stable even if the source archive changes its private timer declaration.
mod_file = ROOT / 'NeoplasmMod.java'
mod_text = mod_file.read_text(encoding='utf-8')
bridge = ('    private static final NeoplasmMod host = new NeoplasmMod();\\n'
          '    public static void loadContentFromHost() { host.loadContent(); }\\n'
          '    public static void initFromHost() { host.init(); }\\n\\n')
if 'public static void loadContentFromHost()' not in mod_text:
    anchor = '    @Override\\n    public void loadContent() {'
    if anchor not in mod_text:
        raise SystemExit('NeoplasmMod loadContent anchor missing.')
    mod_text = mod_text.replace(anchor, bridge + anchor, 1)
mod_file.write_text(mod_text, encoding='utf-8')

# Read values from the existing Restoration settings menu.
old_options = '''    private void readOptions() {
        try {
            var loaded = Vars.mods.getMod(getClass());
            if (loaded != null && loaded.root.child("mod.hjson").exists()) {
                Jval meta = Jval.read(loaded.root.child("mod.hjson").readString());
                enableRemovedLatum = meta.getBool("enableRemovedLatum", false);
                neoplasmDebugAI = meta.getBool("neoplasmDebugAI", false);
                maxBlobsPerHeart = Math.max(1, meta.getInt("maxBlobsPerHeart", 12));
            }
        } catch (Throwable ignored) {
            enableRemovedLatum = false;
            neoplasmDebugAI = false;
            maxBlobsPerHeart = 12;
        }
        neoplasmDebugAI |= Core.settings.getBool("neoplasmDebugAI", false);
    }
'''
new_options = '''    private void readOptions() {
        enableRemovedLatum = Core.settings.getBool("mass-enable-removed-latum", false);
        neoplasmDebugAI = Core.settings.getBool("neoplasmDebugAI", false);
        maxBlobsPerHeart = Math.max(1, Core.settings.getInt("mass-max-blobs-per-heart", 12));
    }
'''
replace('NeoplasmMod.java', old_options, new_options)

# Wire the feature lifecycle to Project: Restoration's existing Mod lifecycle.
host_file = Path('src/classicMod/ClassicMod.java')
host_text = host_file.read_text(encoding='utf-8')
old_init = 'themass.NeoplasmMod.init();'
old_load = 'themass.NeoplasmMod.loadContent();'
if old_init in host_text:
    host_text = host_text.replace(old_init, 'themass.NeoplasmMod.initFromHost();')
if old_load in host_text:
    host_text = host_text.replace(old_load, 'themass.NeoplasmMod.loadContentFromHost();')
if 'themass.NeoplasmMod.initFromHost();' not in host_text:
    raise SystemExit('ClassicMod init lifecycle hook missing.')
if 'themass.NeoplasmMod.loadContentFromHost();' not in host_text:
    raise SystemExit('ClassicMod loadContent lifecycle hook missing.')
host_file.write_text(host_text, encoding='utf-8')

print('Applied The Mass v160.7 API and host-lifecycle fixes.')
