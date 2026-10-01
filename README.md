# Próxima Carrera — fondo de pantalla animado

Cuenta regresiva en vivo hasta la próxima carrera del campeonato, con un circuito abstracto
animado (distinto en cada Gran Premio), hora local de cada sesión y "semáforo" en los
últimos 5 minutos.

- Calendario: Jolpica (api.jolpi.ca), se descarga y guarda; se revisa cada ~6 h.
- Toca el fondo (zona vacía de la pantalla de inicio) para ver prácticas, clasificación y sprint.
- Solo se anima mientras está visible: no gasta batería con la pantalla apagada.
- Ajustes en la app: color de acento, posición, segundos, circuito, firma.

Compilación: GitHub Actions (.github/workflows/build.yml) → artefacto ProximaCarrera-apk.
