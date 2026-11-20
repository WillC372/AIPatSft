# Aqua Assistant AI · Servicio de IA

Servicio de inteligencia artificial de **Aqua Assistant AI**, el sistema para medir y cuidar el pH del agua. Aquí vive todo lo que hace Aqua, la gota con cara de la aplicación: el chat con memoria y citas, el diagnóstico, la detección de anomalías, las tendencias y la predicción, las recomendaciones con respaldo normativo, el simulador de escenarios, los modelos entrenados con los datos de cada lugar (Isolation Forest y red LSTM), el agente de informes, los informes en PDF, la lectura de pH desde una foto, la biblioteca normativa, el consumo y los costos de la IA, y las traducciones de la interfaz.

| Repositorio | Qué es | Dónde corre |
|---|---|---|
| [FrontendPatSft](https://github.com/WillC372/FrontendPatSft) | La aplicación web | Vercel |
| [BackendPatSft](https://github.com/WillC372/BackendPatSft) | API principal: cuentas, mediciones, lugares, cuidado, alertas, pagos, datos oficiales | Render |
| **AIPatSft** (este) | Servicio de IA | Render |
| [DBPatSft](https://github.com/WillC372/DBPatSft) | Esquema y migraciones de la base de datos | Neon (PostgreSQL) |

## Cómo se conecta con el resto

- **Misma base de datos.** Lee de la base compartida las cuentas, los lugares, los perfiles, las mediciones y las alertas que guarda el backend, y escribe sus propias tablas: diagnósticos, conversaciones, informes del agente, consumo de IA y traducciones. Ninguno de los dos servicios crea tablas: el esquema lo aplica [DBPatSft](https://github.com/WillC372/DBPatSft). Qué tabla escribe cada servicio: [Esquema.md](https://github.com/WillC372/DBPatSft/blob/main/docs/Esquema.md).
- **Misma sesión.** El navegador envía el mismo token que le dio el backend al iniciar sesión. Este servicio lo valida con la misma `JWT_SECRET` y comprueba en la base que la sesión siga abierta, así que cerrar sesión o cambiar la contraseña también lo invalida aquí.
- **Llamadas internas**, protegidas con la clave compartida `INTERNAL_API_KEY` (sin ella responden 404):
  - Una lectura de pH por foto que la persona confirma se envía al backend (`POST /api/v1/internal/measurements`). Allí se valida, se clasifica y genera las alertas y avisos como cualquier otra medición.
  - El backend pide aquí la traducción de sus correos y avisos (`POST /api/v1/internal/translate`) cuando no la encuentra en la tabla compartida.
- **Duerme cuando no se usa.** El plan gratuito de Render tiene 750 horas al mes para todos los servicios juntos y el backend las usa para estar siempre despierto. Este servicio se apaga tras 15 minutos sin uso; la aplicación lo despierta en cuanto alguien la abre, así que suele estar listo cuando se toca a Aqua. La primera respuesta después de dormir tarda alrededor de un minuto.
- **Alertas al guardar una medición.** Las crea el backend con reglas fijas (fuera de rango, cambio brusco, valor atípico). El Isolation Forest se usa en las pantallas de análisis, el diagnóstico y el agente de este servicio.

## Arquitectura

```
src/main/java/com/aquaassistant/
├── intelligence/   La IA de Aqua: estadística, reglas, diagnóstico, recomendaciones, escenarios, chat, proveedores de IA
│   ├── agent/      Agente de informes: herramientas, ciclo, verificación de cifras, trabajos en segundo plano
│   ├── ml/         Isolation Forest propio, variables, escalado robusto, modelo por lugar
│   └── deep/       Red LSTM propia para predecir 12 horas
├── knowledge/      Biblioteca normativa: fragmentos literales (EPA, OMS, CDC, FAO) e índice BM25
├── vision/         Lectura de pH desde fotos: saneo de imagen, validación, segunda opinión
├── report/         Informes (Builder), exportación a texto y a PDF (report/pdf: PDFBox y gráficas Java2D)
├── i18n/           Traducción de la interfaz: diccionario por idioma, presupuesto, traducción anticipada
├── measurement/    Lectura de las mediciones (las guarda el backend)
├── profile/, place/, alert/, user/, audit/   Lo que lee de la base compartida y la sesión
├── security/       Validación del token, clave interna, limitador
├── common/         Errores, caché, cliente del backend
└── config/         Seguridad, CORS y propiedades
src/main/resources/
├── knowledge/      Guidelines.json (fuentes y fragmentos)
├── i18n/           ui-texts.txt (textos de la app que se traducen de antemano)
└── fonts/          DejaVu Sans y el subconjunto WenQuanYi Zen Hei para chino y japonés en los PDF
src/test/resources/db/migration/   Copia de las migraciones de DBPatSft para las pruebas
```

## Variables de entorno

| Variable | Obligatoria | Descripción |
|---|---|---|
| `DATABASE_URL` (o `DB_URL`, `DB_USER`, `DB_PASSWORD`) | Sí | La misma base de datos de Neon que el backend |
| `JWT_SECRET` | Sí | **La misma** que la del backend, para aceptar sus sesiones |
| `INTERNAL_API_KEY` | Sí | **La misma** que la del backend (16 caracteres o más). Sin ella las fotos se leen pero no se guardan |
| `BACKEND_URL` | Sí | Dirección del backend (`https://aquasense-backend-willc372.onrender.com`) |
| `CORS_ORIGINS` | No | Orígenes permitidos para el frontend |
| `GEMINI_API_KEY`, `GEMINI_MODEL`, `GEMINI_FALLBACK_MODEL` | No | Google Gemini, proveedor principal (`gemini-3.5-flash` y `gemini-3.5-flash-lite`) |
| `GROQ_API_KEY`, `GROQ_MODEL`, `GROQ_VISION_MODEL` | No | Groq, respaldo (texto `openai/gpt-oss-120b`; visión `qwen/qwen3.8-27b`) |
| `ANTHROPIC_API_KEY`, `AI_MODEL`, `AI_EFFORT` | No | Claude, opción de pago |
| `AI_DAILY_QUOTA_FREE`, `AI_DAILY_QUOTA_PREMIUM` | No | Consultas de IA por usuario y día (20 y 200). Los administradores, incluido el de demostración, no tienen límite |
| `FLYWAY_ENABLED` | No | `false`: el esquema lo aplica DBPatSft |

Sin ninguna clave de IA, el diagnóstico y la conclusión se calculan con reglas y el chat responde "no disponible".

## Funciones

Primero calcula con estadística y reglas de dominio; la IA generativa solo redacta sobre resultados ya calculados.

| Función | Cómo funciona |
|---|---|
| Anomalías | Fuera de rango del perfil, alteración del pH con la misma regla de la alarma del backend (`PhAlterationRule`: cambio de 0,5 o más en una hora, o un valor crítico aunque no haya mediciones recientes) y valor atípico (puntaje z robusto > 3,5 con al menos 10 mediciones previas) |
| Tendencias | Regresión lineal (pH por día), estadísticos y comparación con el periodo anterior. Mínimo 5 mediciones |
| Predicción | Regresión lineal con intervalo de predicción del 95 %. Requiere 10 mediciones y 2 días de historial; si no, responde "datos insuficientes" |
| Recomendaciones | Una estrategia por tipo de perfil (potable, piscina, acuario, riego, general), con los fragmentos normativos que la respaldan |
| Escenarios | Clasifica un pH hipotético y lo compara con el actual, sin guardarlo. **Explicación con IA** (`/scenario/explain`): el sistema calcula cuántas veces cambia la concentración de iones H+ y cuándo se llegaría a ese valor con la tendencia de 7 días; la IA redacta efectos por área, señales tempranas y prevención en JSON estricto, citando normas. Se verifica cada cifra contra los hechos, se descartan citas no entregadas y, si el pH hipotético es crítico, al menos un efecto queda como crítico. Sin IA se genera la misma estructura con reglas |
| **Diagnóstico IA** | El sistema calcula los hechos (mediciones, estadística, anomalías, tendencia, predicción, reglas del perfil). La IA razona sobre ellos y responde un **JSON con esquema estricto**: riesgo, hallazgos con evidencia, causas probables con probabilidad, plan de acción priorizado, datos faltantes, confianza y próxima medición. El `DiagnosisValidator` revisa la respuesta y aplica salvaguardas: limita la confianza según la cantidad y el tipo de datos, y no permite un riesgo menor al de la última medición. Si la respuesta no es válida, se usa `RuleBasedDiagnoser`, que produce la misma estructura. Cada diagnóstico se guarda en `ai_diagnoses` |
| Conclusión y chat | Mismo motor; el chat responde con los datos del usuario y cita las normas recuperadas como `[id]`. **Memoria:** cada conversación se guarda (`chat_conversations`, `chat_messages`) y la IA recibe los últimos 12 mensajes, hasta 9000 caracteres, como turnos reales; así entiende repreguntas como "¿y ese valor es grave?". Los datos del usuario se recalculan en cada turno y prevalecen sobre lo dicho antes. Hasta 50 conversaciones por usuario; se pueden borrar una a una o todas |
| **Consumo y costos** | Cada llamada registra proveedor, modelo, tokens, latencia, si vino de caché y su costo estimado con el precio vigente; los intentos fallidos de cada proveedor también se registran (Observer `AiCallListener`) y no cuentan para la cuota. Se guarda en una transacción independiente para no perder el registro si la operación falla. `/intelligence/ai-usage` resume por modelo, por función y por día; `/admin/ai-usage` da el total global y los usuarios con más consumo. Con `AI_BILLING=FREE` el cargo real es 0 y el costo estimado muestra cuánto costaría con facturación. **Los precios configurados vienen de agregadores de terceros y no se verificaron en las páginas oficiales** (la red del entorno no las alcanza); se ajustan con variables `PRICE_*` |
| **Respaldo normativo (RAG)** | 20 fragmentos **literales** de la EPA, la OMS, la CDC (MAHC 2024) y la FAO, con un resumen propio en español. Un índice BM25 local los recupera filtrando por tipo de perfil, y se citan en recomendaciones, diagnóstico, chat y agente (herramienta `search_guidelines`). El servidor descarta cualquier id que no haya entregado. Para acuarios no hay norma verificada y se muestra una nota en lugar de una cita. Detalle en [`docs/Rag.md`](docs/Rag.md) |

| **Lectura por foto** | El usuario fotografía una tira reactiva o la pantalla de un medidor sin conexión. La imagen se reduce a 1024 px y se recodifica (eso borra los metadatos, incluido el GPS); no se guarda. La IA con visión responde un JSON estricto: tipo de imagen, pH, margen, valores de la carta más parecidos, problemas de la foto y temperatura si se ve. En la pantalla de un medidor que muestra varios números (pH, temperatura, mV) lee solo el que tiene «pH» al lado; si no lo distingue, responde que no se pudo leer en vez de adivinar. El `PhotoReadingValidator` impone la precisión real del método: tira con carta ±0,5 y confianza máxima 0,7; sin carta ±1,0 y 0,35; pantalla ±0,05 y 0,9; cada problema de la foto resta 0,1. En las tiras se pide una **segunda opinión** a otro proveedor: si las dos lecturas difieren en más de 0,5, se amplía el margen y la confianza baja a 0,35. El usuario confirma o corrige el valor antes de guardarlo con origen `PHOTO` |

| **Agente de informes** | El usuario escribe un objetivo, por ejemplo "¿por qué bajó el pH esta semana?" o "informe para una inspección". El agente decide qué consultar con 7 herramientas de solo lectura: `get_water_profile`, `get_statistics`, `get_anomalies`, `get_measurements`, `get_forecast`, `get_alerts` y `get_latest_diagnosis`. Investiga en varios turnos y entrega el informe con `submit_report`: resumen, cifras clave, secciones con la gráfica que elige, recomendaciones y limitaciones. Las herramientas usan el usuario autenticado, nunca un dato del modelo. El ciclo tiene límites (10 turnos, 14 consultas y 2 correcciones). El validador **comprueba cada cifra de pH del texto contra los resultados de las herramientas** y marca las que no coinciden. Corre en segundo plano en un grupo de 2 hilos (Bulkhead), un trabajo por usuario a la vez; la interfaz consulta el avance y muestra cada paso. Si un proveedor falla, la investigación se reinicia con el siguiente; si todos fallan, se genera el informe por reglas. Cada informe se guarda en `agent_reports` con su traza y se puede descargar en PDF o en texto |

| **Aprendizaje automático local** | Un modelo **Isolation Forest** por usuario (Liu, Ting y Zhou, 2008), implementado en Java sin dependencias, en el paquete `intelligence/ml`. Se evaluó la biblioteca Smile, pero desde la versión 3 su licencia es GPL-3.0 y obligaría a distribuir todo el proyecto bajo GPL. El modelo aprende de las mediciones no simuladas de los últimos 90 días: mínimo 30, que cubran 3 días. Usa como variables el pH, el cambio frente a la medición anterior, la velocidad de cambio, la distancia a la mediana de las 10 previas, la hora del día (codificada como seno y coseno) y la temperatura si hay datos. Las variables se escalan con mediana y rango intercuartílico. El modelo tiene 100 árboles, muestras de hasta 256 y un umbral en el 3 % más inusual, nunca menor a 0,6. Cada anomalía se explica con las variables que más se alejan de lo típico. Como `MlAnomalyRule` se suma al detector de este servicio, **la pantalla de anomalías, el diagnóstico y el agente** también lo usan (las alertas al guardar una medición las crea el backend con sus reglas fijas). Isolation Forest no extrapola, así que una medición nueva se evalúa reentrenando con ella incluida (milisegundos). El modelo vive en memoria y se vuelve a entrenar cuando el lugar tiene mediciones nuevas: como las guarda el backend, `PlaceStamps` compara la más reciente y el total de cada lugar antes de usar un modelo |

| **Red neuronal LSTM (capa 7)** | Predice el pH de las próximas 12 horas con una LSTM de una capa (16 unidades ocultas, 1612 parámetros) implementada en Java en el paquete `intelligence/deep`, sin dependencias. Se descartó DJL porque descarga motores nativos de PyTorch desde servidores externos y agrega cientos de MB. Las mediciones se convierten en una serie horaria (huecos de hasta 6 h interpolados) y en ventanas de 24 h de entrada. Los datos se dividen en orden cronológico: 70 % entrenamiento, 15 % validación con detención temprana y 15 % prueba, con 12 h de separación entre partes. En la prueba se compara con tres referencias: persistencia, tendencia lineal y estacional diaria. **La red solo se usa si reduce el error al menos un 5 %**; si no, se usa la mejor referencia y se dice. El intervalo es el percentil 90 del error en la prueba. Genera una alerta anticipada si se prevé salir del rango ideal, y la usan el agente (`get_neural_forecast`) y el diagnóstico. El gradiente se verifica numéricamente en las pruebas. Requiere unos 8 días de mediciones frecuentes; sin ellas no entrena y lo explica. Para verla funcionar antes, se puede entrenar con la serie real de una estación del USGS que el backend importa (`?station=USGS-...`, lista en `/intelligence/deep/stations`): la respuesta dice que son datos oficiales de un río y no las mediciones de la persona |

**Respuestas en vivo.** `POST /intelligence/chat/stream` responde con Server-Sent Events: fragmentos (`delta`) a medida que el modelo escribe, y al final la respuesta completa con citas verificadas (`done`) o un error. Para el chat se prueban primero los modelos rápidos: Gemini Flash-Lite y Groq gpt-oss-120b con razonamiento bajo. Un modelo se salta y se usa el siguiente si:

- no envía la respuesta HTTP en 8 s, o
- no escribe texto en 12 s.

Si una respuesta ya empezada pasa 6 s sin texto nuevo, el servidor envía el evento `restart` y el siguiente modelo la escribe completa. En producción, las pausas normales duran menos de 1,2 s y Gemini Flash-Lite a veces se congela 14 s o más. Un vigilante (`StreamWatchdog`) cierra la conexión porque el cliente HTTP de Java no tiene límite de lectura; las líneas sin texto (mensajes para mantener viva la conexión) no cuentan. Si el modelo corta su propia respuesta (`finish_reason: content_filter`; Gemini lo hace a veces al citar una norma textual), la respuesta no se guarda a medias: se pide al siguiente modelo. Un modelo que falla o se congela en el chat pasa al final de la fila durante 10 minutos (Circuit Breaker), para que las preguntas siguientes no lo esperen. Medido en producción con 12 preguntas: todas terminaron en 10,8 s o menos (antes, hasta 27 s o sin respuesta).

**Proveedores y respaldo.** `FallbackAiProvider` (Chain of Responsibility) intenta en orden Gemini 3.5 Flash, Gemini 3.5 Flash-Lite, Groq gpt-oss-120b y Claude. Pasa al siguiente si uno está saturado (503), sin cuota (429), rechaza la solicitud o devuelve un JSON inválido. Por delante está `AiProviderProxy`, que controla la cuota diaria por plan, la caché y el registro del consumo en `ai_usage`. Solo se envían valores de pH, fechas y datos del perfil; nunca nombres ni correos. Para fotos solo participan los proveedores con visión (`vision: true`): Gemini Flash, Gemini Flash-Lite y Groq Qwen 3.8. Gemini y Groq se usan con su endpoint compatible con OpenAI (`Authorization: Bearer`), por eso una sola clase (`OpenAiCompatibleProvider`) sirve para ambos.

**Retry.** Si un proveedor responde 429 o 503 indicando una espera de 20 segundos o menos (`Retry-After` o `x-ratelimit-reset-*` de Groq), se espera y se reintenta una vez. Si la espera es larga, por ejemplo con la cuota diaria agotada, se pasa de inmediato al siguiente proveedor.

**Verificado con llamadas reales (7 nov 2026).** Gemini 3.5 Flash generó un diagnóstico validado en unos 16 s. Con los modelos de Gemini forzados a fallar, la cadena respondió con Groq gpt-oss-120b. El chat respondió con Gemini usando las mediciones reales. Con imágenes sintéticas de prueba, la lectura por foto leyó una tira como 6.1 (Gemini 6.0 y Groq Qwen 6.2, coincidentes) y una pantalla como 7.42 a 24.6 °C, y rechazó una imagen sin relación con el agua. El agente de informes, con la cuota diaria de Gemini Flash agotada, reinició la investigación con Gemini Flash-Lite. Hizo entre 5 y 7 consultas según el objetivo, terminó en 4 a 7 segundos y todas sus cifras se verificaron (22/22 y 17/17). **Falta probarla con fotos reales de tiras**: la precisión con iluminación y cámaras reales no está medida. Los planes gratuitos tienen límites que cambian y que se consultan en AI Studio y en la consola de Groq.


## Explicación de las alarmas

Cuando el backend crea una alarma por alteración del pH, la app pide aquí una explicación más completa (`POST /api/v1/intelligence/alarm/{alertId}/explain`, `intelligence/alarm/AlarmExplainer.java`). El sistema reúne los datos: las mediciones de la última hora en ese lugar, el perfil y sus rangos, si es peligroso (lo decide el sistema: nivel crítico = peligroso) y los fragmentos de normas que aplican. La IA escribe, en JSON estricto y en el idioma de la persona, un resumen, las causas probables para ese tipo de agua, los pasos a seguir y cuándo vuelve a ser seguro, sin contradecir el veredicto ni dar dosis exactas. Si la IA no está disponible o se acabó la cuota, la misma estructura sale de las reglas del perfil. Cuenta como una consulta de IA («Explicación de alarmas» en Consumo y costos).

## Traducciones de la interfaz

La aplicación se muestra en español, inglés, francés, alemán, italiano, chino simplificado y japonés (`i18n/`). Cada texto se traduce una vez por idioma con la IA y se guarda en la tabla compartida `ui_translations`; la aplicación descarga el diccionario de su idioma (`GET /api/v1/i18n/dictionary/{lang}`) y pide los textos nuevos (`POST /api/v1/i18n/translate`).

- `i18n/ui-texts.txt` lista los textos de la aplicación, de los mensajes de los dos servicios, de la base de conocimiento y de los perfiles del sistema. Se genera con `java scripts/ExtractUiTexts.java ../FrontendPatSft ../BackendPatSft . ../DBPatSft src/main/resources/i18n/ui-texts.txt`, que lee los cuatro repositorios. `UiTextWarmer` los mantiene traducidos de antemano en lotes pequeños.
- Las traducciones comparten la cuota gratuita de la IA con las respuestas de Aqua y nunca la agotan: pueden usar 60 llamadas al día (`aquasense.i18n.ai-calls-per-day`), la traducción anticipada solo el 60 % de ellas, y se pausan 3 horas cuando un proveedor avisa que llegó a su límite. Un presupuesto por dirección y por día limita los textos nuevos que llegan desde fuera.
- El diccionario es público, así que nunca guarda datos personales: la aplicación no envía lo que escribe cada persona (nombres, correos, lugares, notas, preguntas del chat) y el servicio rechaza cualquier texto con un correo.
- Una traducción con otro trato («vous», «Sie», «Lei», «您») se pide de nuevo.
- Si la IA devuelve el texto en español sin cambios, no se guarda y se vuelve a pedir más tarde (la migración V25 borró los que habían quedado así).

## Informes en PDF

Dos informes se descargan en PDF desde la interfaz: el del agente de IA (botón "Descargar PDF" en el informe) y el informe del periodo por reglas (botón "Informe PDF" en el historial).

| Ruta | Contenido |
|---|---|
| `GET /api/v1/intelligence/agent-reports/{id}/pdf` | Objetivo, resumen, cifras clave, sello de cifras verificadas, secciones con la gráfica que eligió el agente, recomendaciones con prioridad, limitaciones, citas normativas literales y los pasos que siguió el agente |
| `GET /api/v1/reports/pdf?days=7&includeAi=false` | Resumen del periodo, gráfica de evolución, mediciones por estado, tendencia, anomalías marcadas, predicción con su intervalo, recomendaciones y conclusión |

- Las gráficas se dibujan en el servidor con los mismos datos guardados: franja del rango ideal del perfil, anomalías marcadas y banda de predicción. El eje de tiempo se ajusta al periodo (días, horas o segundos).
- La fuente va incrustada en el archivo, así las tildes y la "ñ" se ven igual en cualquier equipo.
- Sale en el idioma de la persona: una primera pasada reúne los textos, se traducen juntos y la segunda los escribe. El chino y el japonés se dibujan con un subconjunto de WenQuanYi Zen Hei (`fonts/WqyZenHeiCjk.ttf`, 3,5 MB, GB2312 y JIS X 0208; licencia GPL con excepción de incrustación en `fonts/WqyZenHeiLicense.txt`), solo para los caracteres que DejaVu no tiene, también en las etiquetas de las gráficas.
- Si el periodo incluye lecturas del simulador, el PDF lo advierte en un recuadro "Datos simulados".
- Cada página lleva el aviso "Informe orientativo basado en el pH; no reemplaza un análisis de laboratorio" y su número.
- Un usuario solo puede descargar sus propios informes; el de otro usuario responde 404.


## Ejecución local

1. Una base con el esquema: `docker compose up` en [DBPatSft](https://github.com/WillC372/DBPatSft).
2. El backend en el puerto 8080 y este servicio en el 8081, con la misma `JWT_SECRET` y la misma `INTERNAL_API_KEY`:

```bash
cp .env.example .env
```

```bash
PORT=8081 mvn spring-boot:run
```

El frontend local ya apunta a `http://localhost:8081/api/v1` para todo lo de la IA.

## Pruebas

```bash
mvn test
```

128 pruebas; GitHub Actions las corre en cada cambio de `main` (`.github/workflows/Tests.yml`). Último resultado (20 nov 2026): 128/128 aprobadas. Usan H2 en modo PostgreSQL con una copia de las migraciones (`java scripts/SyncMigrations.java ../DBPatSft` la actualiza). Las cuentas y las mediciones de prueba se escriben directamente en la base de prueba, igual que las crea el backend en producción (`support/TestAccounts`), y el backend se reemplaza por un servidor local que responde como su ruta interna (`support/FakeBackend`). Los proveedores de IA se imitan con un servidor HTTP local (`support/FakeAiServer`).

## Despliegue

`render.yaml` crea el servicio en Render (Docker, plan gratuito) con su verificación de salud (`/actuator/health`). Al crearlo se escriben en el panel de Render `DATABASE_URL`, `JWT_SECRET` e `INTERNAL_API_KEY` (los mismos valores que el backend) y las claves de Gemini y Groq; nunca se guardan en el repositorio.

| Parte | Dirección |
|---|---|
| Servicio de IA | https://aquasense-ai-willc372.onrender.com/api/v1 (salud: `/actuator/health`) |

## Licencia

Proyecto académico de Patrones de Software. Sin licencia de uso definida por el autor.
