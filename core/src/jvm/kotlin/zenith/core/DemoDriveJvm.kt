package zenith.core

/** Scenario from the tile bundled in core's resources (JVM; iOS bundles the same file and calls [DemoDrive.scenario]). */
fun DemoDrive.load(): DemoScenario {
    val text = DemoDrive::class.java.getResource(DemoDrive.TILE)?.readText() ?: error("demo tile ${DemoDrive.TILE} missing")
    return scenario(text)
}
