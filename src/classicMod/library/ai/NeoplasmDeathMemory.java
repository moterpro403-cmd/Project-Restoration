package classicMod.library.ai;

import arc.Events;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.math.geom.Vec2;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import classicMod.library.GeometryPlus;
import mindustry.Vars;
import mindustry.entities.units.AIController;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.type.unit.NeoplasmUnitType;
import mindustry.world.Tile;

/**
 * Shared, team-scoped memory of tiles where Neoplasm units have died.
 *
 * Deaths are recorded from UnitDestroyEvent, so the tracker does not depend on
 * another nearby unit using a custom AI controller to notice the death.
 */
public final class NeoplasmDeathMemory {
    private static final float avoidanceRadius = 80f;

    private static final ObjectMap<Team, Seq<Tile>> dodgeTiles = new ObjectMap<>();
    private static final ObjectMap<Team, Seq<Tile>> deathCenters = new ObjectMap<>();

    private static final Vec2 escape = new Vec2();
    private static final Vec2 desired = new Vec2();

    private static boolean loaded;

    private NeoplasmDeathMemory() {
    }

    public static void load() {
        if (loaded) return;
        loaded = true;

        Events.on(EventType.UnitDestroyEvent.class, event -> recordDeath(event.unit));
        Events.on(EventType.WorldLoadBeginEvent.class, event -> clear());
        Events.on(EventType.ResetEvent.class, event -> clear());
    }

    /** Returns the live danger-tile list for a team. */
    public static Seq<Tile> dodgeTiles(Team team) {
        Seq<Tile> result = dodgeTiles.get(team);
        if (result == null) {
            result = new Seq<>();
            dodgeTiles.put(team, result);
        }
        return result;
    }

    private static Seq<Tile> deathCenters(Team team) {
        Seq<Tile> result = deathCenters.get(team);
        if (result == null) {
            result = new Seq<>();
            deathCenters.put(team, result);
        }
        return result;
    }

    private static void clear() {
        dodgeTiles.clear();
        deathCenters.clear();
    }

    private static void recordDeath(Unit unit) {
        if (unit == null || !(unit.type instanceof NeoplasmUnitType)) return;

        AIController ai = null;
        if (unit.controller() instanceof NeoplasmDeathAvoidanceAI wrapper) {
            ai = wrapper.delegate();
        } else if (unit.controller() instanceof AIController controller) {
            ai = controller;
        }

        // Preserve the original exception for Neoplasm units that intentionally
        // kill themselves after completing a task or failing to find a vent.
        if (ai instanceof NeoplasmAIController controller && controller.ignore) return;

        Tile center = unit.tileOn();
        if (center == null) return;

        deathCenters(unit.team).addUnique(center);

        Seq<Tile> teamDodgeTiles = dodgeTiles(unit.team);
        for (Point2 offset : GeometryPlus.d8plus) {
            Tile tile = Vars.world.tile(center.x + offset.x, center.y + offset.y);
            if (tile != null) teamDodgeTiles.addUnique(tile);
        }
    }

    /**
     * Applies a small steering correction after the original AI has updated.
     * It does not select targets or replace the unit's original controller.
     */
    public static void applyMovement(Unit unit) {
        if (unit == null || unit.dead || !(unit.type instanceof NeoplasmUnitType)) return;

        Seq<Tile> centers = deathCenters.get(unit.team);
        if (centers == null || centers.isEmpty()) return;

        Tile closest = null;
        float closestDistance = Float.MAX_VALUE;
        for (Tile center : centers) {
            float distance = center.dst(unit);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = center;
            }
        }

        if (closest == null || closestDistance >= avoidanceRadius) return;

        float dx = unit.x - closest.worldx();
        float dy = unit.y - closest.worldy();
        if (dx * dx + dy * dy < 0.0001f) {
            // A deterministic fallback prevents a unit exactly on the death
            // center from receiving a zero-length escape vector.
            escape.trns((unit.id * 137.50776f) % 360f, 1f);
        } else {
            escape.set(dx, dy).nor();
        }

        float speed = unit.speed();
        if (speed <= 0.001f) return;

        float influence = Mathf.clamp(1f - closestDistance / avoidanceRadius);
        float requiredAwaySpeed = speed * (0.2f + influence * 0.35f);
        if (unit.vel().dot(escape) >= requiredAwaySpeed) return;

        // Blend the AI's requested motion with an outward vector. The blend is
        // mild at the edge of the danger zone and stronger near the death site.
        float blend = 0.15f + influence * 0.75f;
        desired.set(unit.vel()).scl(1f - blend)
            .add(escape.x * speed * blend, escape.y * speed * blend)
            .limit(speed);

        if (desired.len2() < 0.0001f) desired.set(escape).scl(speed * 0.5f);

        unit.movePref(desired);
    }
}
