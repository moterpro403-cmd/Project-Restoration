    @Override
    public void loadContent() {
        registerConstructors();

        useNewSound = settings.getBool("content-use-new-sound");
        new RItems().load();
        RVars.initDrillWhitelist();
        new OverridableContent().loadOverride();
        new RBullets().load();
        new RUnitTypes().load();
        new RBlocks().load();
        new RSectorPresents().load();
        new RPlanets().load();

        new RSerpuloTechTree().load();
        new ExtendedErekirTechTree().load();