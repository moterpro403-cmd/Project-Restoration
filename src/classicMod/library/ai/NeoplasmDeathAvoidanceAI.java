package classicMod.library.ai;

import arc.math.geom.Vec2;
import mindustry.entities.units.AIController;
import mindustry.game.UnitStance;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;

/**
 * Runtime decorator for Neoplasm AI. It preserves and runs the original
 * controller, then applies shared death-memory steering.
 */
public final class NeoplasmDeathAvoidanceAI extends AIController {
    private final AIController delegate;

    public NeoplasmDeathAvoidanceAI(AIController delegate) {
        this.delegate = delegate;
    }

    public AIController delegate() {
        return delegate;
    }

    @Override
    public void unit(Unit unit) {
        super.unit(unit);
        delegate.unit(unit);
    }

    @Override
    public void updateUnit() {
        delegate.updateUnit();
        NeoplasmDeathMemory.applyMovement(unit);
    }

    @Override
    public void afterRead(Unit unit) {
        delegate.afterRead(unit);
    }

    @Override
    public boolean isLogicControllable() {
        return delegate.isLogicControllable();
    }

    @Override
    public boolean keepState() {
        return delegate.keepState();
    }

    @Override
    public boolean hasStance(UnitStance stance) {
        return delegate.hasStance(stance);
    }

    @Override
    public void stanceChanged() {
        delegate.stanceChanged();
    }

    @Override
    public void stopShooting() {
        delegate.stopShooting();
    }

    @Override
    public void commandTarget(Teamc target) {
        delegate.commandTarget(target);
    }

    @Override
    public void commandPosition(Vec2 position) {
        delegate.commandPosition(position);
    }
}
