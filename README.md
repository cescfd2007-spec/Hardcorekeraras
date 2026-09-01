# HardcoreWorldReset

Plugin para Paper: cuando muere cualquier jugador, crea un mundo nuevo con semilla aleatoria,
teletransporta a todos allí y limpia inventarios/experiencia/efectos para empezar desde cero.

## Importante
No pongas `hardcore=true` en `server.properties`. El plugin implementa la regla de una vida y
el reinicio global; el modo hardcore nativo puede dejar al jugador muerto en espectador/bloquearlo.

## Compilación
El workflow de GitHub Actions compila automáticamente el `.jar` con Java 21.
