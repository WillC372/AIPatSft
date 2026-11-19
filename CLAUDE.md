# AIPatSft · Aqua Assistant AI

Servicio de inteligencia artificial de Aqua Assistant AI (Spring Boot, Java 21, Maven), publicado en Render desde `main`.

## Regla principal

Aqua Assistant AI ya existe, tiene cuatro repositorios (FrontendPatSft, BackendPatSft, AIPatSft y DBPatSft) y está desplegado. **Analizar, corregir, optimizar y mejorar; nunca reconstruir.** No se crea otra versión de la app, no se reemplaza el frontend, el backend ni la base de datos, no se quitan funcionalidades que funcionan y no se cambian la identidad visual, los diseños ni la navegación sin autorización del dueño.

## Equipo de ingeniería

El equipo multiagente (`/equipo-ingenieria` y los especialistas de `.claude/agents/`) audita, corrige y mejora el producto. Sus registros están en FrontendPatSft: `docs/calidad/hallazgos.md`, `docs/calidad/registro-de-cambios.md`, `docs/calidad/propuestas/` y la guía `docs/calidad/README.md`.

## Reglas de trabajo

- Entender antes de cambiar: leer el código actual, sus pruebas y su documentación.
- Cambios estructurales importantes (arquitectura, contratos entre servicios, esquema, navegación, identidad visual, dependencias nuevas): primero una propuesta con problema, evidencia técnica, impacto, alternativas, ventajas y desventajas y solución recomendada (`docs/calidad/plantilla-propuesta.md` de FrontendPatSft). Se espera la aprobación del dueño.
- Nunca en `main`: ramas `mejora/H-XXX-descripcion` (o `calidad/...` para documentación del equipo) y pull request. El merge publica en producción y lo hace el dueño. `.claude/hooks/ProtegerMain.java` bloquea commits y pushes a `main`, push forzado y `reset --hard`.
- Sin despliegues, sin cambiar credenciales, secretos ni variables de Render, Vercel, Neon o GitHub.
- Base de datos: solo migraciones nuevas y aditivas en DBPatSft; una migración aplicada nunca se edita; nada destructivo sin aprobación.
- Pruebas antes y después de cada cambio, con los números; cada cambio queda en el registro con cómo revertirlo.
- Sin datos simulados presentados como reales, sin integraciones fingidas ni funcionalidades inventadas. Las cuentas temporales de prueba en la app publicada se borran al terminar.
- Commits con la identidad de git del dueño, mensajes en español que expliquen el porqué y sin líneas Co-Authored-By. Documentación en español; comentarios de código en inglés.

## Este repositorio

- `intelligence` (Aqua: chat, diagnóstico, escenarios, agente, alarmas, Isolation Forest, LSTM), `vision` (lectura por foto), `knowledge` (biblioteca normativa), `report`, `i18n` (traducciones), `intelligence/ai` (proveedores, cuota, costos).
- Pruebas: `mvn test` (H2 con copia de las migraciones; proveedores y backend imitados con servidores locales). GitHub Actions las corre en cada push a `main` y en cada pull request.
- La IA nunca contradice el veredicto que calcula el sistema y siempre tiene un respaldo por reglas.
