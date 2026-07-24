# Spike — Proveedores de datos para VideoGame Platform

- **Estado:** Investigación documental completada; umbrales y muestra aprobados; prueba autenticada pendiente
- **Fecha:** 2026-07-23
- **Última actualización:** 2026-07-24
- **Fase:** 0 — Product alignment / preparación de Phase 1
- **Decisión estudiada:** Seleccionar un proveedor candidato para alimentar el catálogo inicial
- **Proveedores evaluados:** IGDB y RAWG
- **Resultado recomendado:** Ejecutar la primera prueba autenticada con IGDB y conservar RAWG como alternativa

> Este spike no constituye una revisión legal. Las condiciones comerciales, la licencia de imágenes y la posibilidad de mostrar puntuaciones de terceros deben confirmarse por escrito con el proveedor antes de un lanzamiento público o monetizado.

## 1. Resumen ejecutivo

El learning MVP aprobado necesita importar datos desde **un único proveedor autorizado** para soportar:

- lanzamientos recientes o semanales;
- próximos lanzamientos;
- búsqueda por título;
- ficha básica del videojuego;
- plataformas, géneros, compañías, fechas e imágenes;
- trazabilidad del origen y frescura de los datos.

Los dos proveedores analizados pueden cubrir ese recorrido, pero presentan perfiles distintos:

- **IGDB** ofrece el modelo más rico y adecuado para construir un catálogo propio desacoplado. Modela fechas por plataforma y región, localizaciones, idiomas soportados, compañías, franquicias, imágenes y relaciones entre juegos. Permite cachear y almacenar localmente los datos, dispone de consultas incrementales y webhooks, y no anuncia un límite mensual, aunque aplica 4 solicitudes por segundo. Su integración es menos convencional y requiere OAuth de Twitch, consultas POST con APICalypse y una aclaración comercial o partnership antes de monetizar.
- **RAWG** es más rápido de integrar porque ofrece una API REST basada en GET y API key. Publica filtros directos por fecha, plataforma, género y puntuación, así como descripciones, imágenes, tiendas y datos de Metacritic. Sin embargo, su página actual combina una tabla de precios que limita el plan gratuito a proyectos no comerciales con unos términos inferiores que todavía afirman cierto uso comercial gratuito. Esta contradicción, el límite de 20.000 solicitudes mensuales, la atribución obligatoria por página y la prohibición de redistribución elevan el riesgo.

### Recomendación

**Usar IGDB como candidato principal para el PoC técnico y, si supera las validaciones de cobertura, calidad y condiciones de uso, adoptarlo como proveedor inicial del MVP.**

RAWG debe mantenerse como alternativa por su sencillez y por algunos datos adicionales, pero no debería seleccionarse sin aclarar por escrito:

1. qué condiciones comerciales son realmente vigentes;
2. qué plan necesita el producto;
3. qué uso está permitido para imágenes y puntuaciones externas;
4. cómo debe implementarse exactamente la atribución.

La elección de IGDB no elimina el trabajo de producto en español. Ninguno de los dos proveedores garantiza descripciones editoriales completas en español. La plataforma deberá conservar una capa propia de contenido localizado y no confundir “idioma soportado por el juego” con “ficha editorial traducida”.

## 2. Contexto del producto

El Product Brief version 0.2 considera como alcance aprobado:

- vista de lanzamientos recientes o semanales;
- búsqueda por título;
- ficha con información esencial;
- importación desde un proveedor autorizado;
- modelo interno no acoplado al proveedor;
- trazabilidad y estado de sincronización;
- puntuaciones externas únicamente cuando la licencia lo permita.

Este spike responde principalmente al riesgo de **disponibilidad y licencia de datos**, identificado como uno de los riesgos más importantes del producto.

## 3. Preguntas del spike

1. ¿Puede el proveedor alimentar el recorrido principal del MVP?
2. ¿Permite obtener fechas suficientemente precisas por plataforma y región?
3. ¿Proporciona imágenes y metadatos esenciales con condiciones de uso razonables?
4. ¿Puede sincronizarse de manera incremental y resiliente?
5. ¿Permite almacenar datos localmente y servirlos desde nuestra propia aplicación?
6. ¿Su autenticación, límites y modelo de consulta son asumibles para un backend Java/Spring?
7. ¿Aporta algo útil para una experiencia orientada a comunidad hispanohablante?
8. ¿Qué incertidumbres legales o comerciales bloquean una decisión definitiva?

## 4. Alcance y limitaciones

### Incluido

- Investigación de documentación oficial disponible el 23 de julio de 2026.
- Comparación funcional, técnica, operativa y comercial.
- Diseño preliminar de integración desacoplada.
- Ejemplos de consultas y criterios para una prueba autenticada.
- Recomendación y gates de decisión.

### No incluido

- Alta de cuentas o contratación de planes.
- Pruebas autenticadas, porque no se proporcionaron credenciales.
- Medición real de latencia, disponibilidad o calidad de resultados.
- Revisión jurídica de licencias.
- Validación exhaustiva de cobertura mediante una muestra real de juegos.
- Integración con Metacritic, OpenCritic u otras puntuaciones externas.

Por tanto, la conclusión es suficiente para elegir el **orden de experimentación**, pero todavía no para aprobar definitivamente un proveedor de producción.

## 5. Criterios de evaluación

| Criterio | Peso | Qué se valora |
|---|---:|---|
| Cobertura funcional del MVP | 25% | Juegos, búsqueda, fichas, imágenes, plataformas, géneros y compañías |
| Lanzamientos y frescura | 15% | Fechas por plataforma/región, cambios de fecha y sincronización incremental |
| Simplicidad de integración | 10% | Autenticación, protocolo, documentación y ergonomía desde Java/Spring |
| Claridad legal y comercial | 20% | Uso permitido, atribución, almacenamiento, imágenes, monetización y precio |
| Encaje con español/localización | 10% | Títulos localizados, regiones e idiomas soportados |
| Escalabilidad y operación | 10% | Límites, paginación, cache, webhooks, bulk e independencia del frontend |
| Coste durante el MVP | 10% | Coste inicial y riesgo de escalado del plan |

## 6. Proveedor 1 — IGDB

### 6.1 Descripción

IGDB es una base de datos de videojuegos operada dentro del ecosistema de Twitch. Su API v4 expone un modelo extenso de entidades relacionadas y utiliza un lenguaje de consulta propio, **APICalypse**, enviado normalmente mediante peticiones POST.

### 6.2 Encaje con el MVP

| Necesidad | Soporte observado | Valoración |
|---|---|---|
| Buscar juegos | Endpoint de búsqueda y consultas sobre `games` | Alto |
| Lanzamientos semanales | `release_dates` con fecha, juego, plataforma, región y estado | Muy alto |
| Próximos lanzamientos | Filtrado por fecha y estado | Muy alto |
| Ficha básica | Nombre, resumen, portada, plataformas, géneros, compañías y webs | Muy alto |
| Imágenes | Portadas, artworks y screenshots mediante `image_id` | Alto |
| Franquicias y relaciones | Franquicias, colecciones, DLC, expansiones, remakes y remasters | Muy alto, aunque parte queda fuera del MVP |
| Idiomas del juego | `language_supports` y tipos de soporte | Alto |
| Títulos/cubiertas localizados | `game_localizations` por región | Medio-alto |
| Puntuaciones externas | Campos de rating de usuarios y agregados | Existe, pero debe quedar fuera del MVP hasta validar licencia y significado |
| Sincronización incremental | `updated_at`, consultas filtradas y webhooks | Muy alto |

### 6.3 Autenticación e integración

Requisitos:

1. Cuenta de Twitch con autenticación de doble factor.
2. Aplicación registrada como cliente confidencial.
3. `Client ID` y `Client Secret`.
4. Token OAuth 2.0 obtenido mediante `client_credentials`.
5. Cabeceras `Client-ID` y `Authorization: Bearer ...` en cada petición.

Características técnicas relevantes:

- La API no permite llamadas directas desde el navegador por CORS y para evitar exponer el token. Esto encaja con la arquitectura propuesta: el frontend debe consumir nuestra API, nunca IGDB directamente.
- Límite publicado: **4 solicitudes por segundo** y hasta **8 solicitudes abiertas simultáneamente**.
- Máximo de **500 elementos por petición**.
- Multi-query permite agrupar varias consultas en una sola llamada.
- Los tokens expiran y deben renovarse; la documentación indica una vida aproximada de 60 días y un máximo de 25 tokens activos por aplicación.
- IGDB recomienda almacenar y servir localmente los datos en vez de usar su API como backend en tiempo real.

### 6.4 Ejemplo de consulta para próximos lanzamientos

```http
POST https://api.igdb.com/v4/release_dates
Client-ID: ${IGDB_CLIENT_ID}
Authorization: Bearer ${IGDB_ACCESS_TOKEN}
Accept: application/json

fields date,human,game.id,game.name,game.slug,game.cover.image_id,
       platform.id,platform.name,release_region.region,status.name;
where date >= ${FROM_EPOCH}
  & date < ${TO_EPOCH};
sort date asc;
limit 500;
```

> La sintaxis exacta de expansión debe validarse en la prueba autenticada. El ejemplo representa la consulta objetivo, no evidencia de ejecución.

### 6.5 Estrategia de sincronización propuesta

- **Carga inicial acotada:** importar solamente juegos relevantes para una ventana temporal, no intentar descargar todo el catálogo.
- **Incremental por `updated_at`:** consultar cambios desde el último watermark confirmado.
- **Reconciliación diaria:** volver a consultar la ventana de próximos lanzamientos, porque fechas futuras pueden cambiar.
- **Webhooks como optimización posterior:** no depender de ellos en el primer slice; incorporarlos cuando la carga periódica esté estable.
- **Cache/almacenamiento propio:** persistir el modelo normalizado y no realizar fan-out a IGDB en cada vista de usuario.
- **Rate limiting local:** token bucket de 3 solicitudes/segundo para mantener margen respecto al límite de 4.

### 6.6 Condiciones de uso y riesgo comercial

La documentación oficial contiene dos mensajes que deben interpretarse con cautela:

- En “Getting Started” indica uso gratuito no comercial bajo el acuerdo de desarrolladores de Twitch y solicita contactar para necesidades comerciales.
- En su FAQ de partnership afirma que la API es gratuita tanto para proyectos no comerciales como comerciales, pero que los productos monetizados deben integrarse mediante partnership y ofrecer atribución visible.

También afirma que:

- se permite almacenar/cachear los datos localmente;
- se prefiere que el integrador sirva los datos desde su propia infraestructura;
- la atribución debe ser visible;
- los datos ya recuperados pueden conservarse si termina la partnership.

**Conclusión legal provisional:** técnicamente favorable, pero antes de monetizar debe obtenerse confirmación escrita de IGDB sobre partnership, atribución, imágenes y campos de ratings utilizados.

### 6.7 Español y localización

IGDB aporta dos capacidades útiles:

- nombres y portadas localizados por región;
- información sobre los idiomas soportados por el juego.

No se observa una garantía de que `summary` o `storyline` estén disponibles en español. Por ello:

- `providerSummary` debe almacenarse con idioma conocido o marcado como desconocido;
- la descripción editorial española debe ser un campo propio y separado;
- no se debe traducir automáticamente y presentar el resultado como contenido oficial del proveedor;
- los títulos alternativos/localizados pueden mejorar búsquedas en español.

### 6.8 Ventajas

- Modelo de datos muy completo y normalizado.
- Fechas detalladas por plataforma y región.
- Buen soporte para sincronización incremental.
- Permite almacenamiento local, alineado con un modelo de dominio propio.
- Sin límite mensual explícito; el control principal es por segundo.
- Incluye localizaciones e idiomas soportados.
- Encaja bien con una integración backend y un adaptador anticorrupción.

### 6.9 Inconvenientes

- Autenticación más compleja mediante Twitch OAuth.
- Lenguaje APICalypse menos estándar que REST con query parameters.
- Límite de 4 solicitudes por segundo.
- Cambios de esquema y campos deprecados requieren contract tests.
- Condiciones comerciales y partnership deben confirmarse.
- Las descripciones no resuelven la propuesta editorial en español.

## 7. Proveedor 2 — RAWG

### 7.1 Descripción

RAWG ofrece una API REST de catálogo con autenticación mediante API key. Su web anuncia más de 500.000 juegos, datos para unas 50 plataformas y un volumen elevado de screenshots, ratings, desarrolladores y publishers.

### 7.2 Encaje con el MVP

| Necesidad | Soporte observado | Valoración |
|---|---|---|
| Buscar juegos | `GET /api/games?search=...` con búsqueda precisa o exacta | Alto |
| Lanzamientos semanales | Filtros `dates` y `platforms` | Alto |
| Próximos lanzamientos | Rango de fechas y ordenación | Alto |
| Ficha básica | Descripción, géneros, fechas, tiendas, ESRB, webs y requisitos | Alto |
| Imágenes | Backgrounds y screenshots | Alto, condicionado por licencia/atribución |
| Franquicias/DLC | Juegos padre, DLC y series | Medio-alto |
| Idiomas del juego | No aparece como fortaleza publicada equivalente a IGDB | Bajo-medio |
| Títulos/cubiertas localizados | No se observa garantía oficial | Bajo |
| Puntuaciones externas | Ratings propios y datos de Metacritic, incluso por plataforma | Funcionalmente alto, legalmente pendiente |
| Sincronización incremental | Fecha de última actualización en detalle y filtros de consulta | Medio |

### 7.3 Autenticación e integración

- API key incluida como query parameter en cada petición.
- API REST convencional basada en GET.
- Filtros directos por fechas, plataformas, desarrolladores, géneros, tags y Metacritic.
- Paginación estándar.
- Integración inicial más sencilla que IGDB.

Ejemplo:

```http
GET https://api.rawg.io/api/games
    ?key=${RAWG_API_KEY}
    &dates=2026-07-20,2026-07-26
    &ordering=released
    &page_size=40
```

### 7.4 Límites y precio publicado

La sección de pricing visible el 23 de julio de 2026 indica:

- **Free:** proyectos personales y hobby, no comerciales, hasta 20.000 solicitudes al mes y backlinks obligatorios.
- **Business:** 149 USD/mes, uso comercial, hasta 50.000 solicitudes al mes, datos adicionales y soporte por email.
- **Enterprise:** hasta 1.000.000 de solicitudes al mes, descarga de archivos y condiciones personalizadas.

Sin embargo, la sección “Terms of Service” de la misma página todavía afirma que startups y proyectos hobby pueden usar gratuitamente la API con fines comerciales hasta 100.000 usuarios activos mensuales o 500.000 páginas vistas mensuales.

**Esta contradicción debe considerarse un bloqueo contractual hasta que RAWG confirme por escrito cuál es la regla vigente.** La tabla de pricing es más específica y aparentemente más reciente, por lo que este spike no presupone uso comercial gratuito.

### 7.5 Atribución, almacenamiento y redistribución

RAWG exige:

- atribuir RAWG como fuente de datos e imágenes;
- añadir un enlace activo desde cada página en la que se utilicen esos datos;
- no redistribuir ni revender los datos a terceros.

La atribución por página afecta directamente al diseño de la ficha y de la vista de lanzamientos. Debe ser parte de los acceptance criteria, no un texto escondido en el footer legal.

La prohibición de redistribución no impide necesariamente servir datos dentro del propio producto, pero obliga a evitar endpoints públicos que reproduzcan el dataset de RAWG sin aportar una capacidad de producto. La interpretación exacta debe confirmarse.

### 7.6 Riesgo de imágenes y puntuaciones externas

RAWG declara que no reclama propiedad sobre todas las imágenes o datos proporcionados y que retira contenido infractor cuando recibe una notificación adecuada. Esto no equivale a una cadena de derechos completa para cada imagen.

Además, que RAWG exponga datos de Metacritic no demuestra por sí solo que VideoGame Platform pueda reutilizarlos en cualquier contexto. Antes de mostrar estas puntuaciones deben confirmarse:

- derechos contractuales;
- atribución;
- frecuencia de actualización;
- posibilidad de almacenamiento;
- uso de marcas y logotipos;
- restricciones de presentación.

Para el MVP se recomienda **no importar Metacritic desde RAWG** hasta resolverlo expresamente.

### 7.7 Español y localización

La documentación comercial de RAWG no presenta el contenido localizado en español como una capacidad principal. Puede ayudar a descubrir juegos y obtener metadatos universales, pero no debe asumirse que proporciona:

- descripciones en español;
- títulos regionalizados completos;
- clasificación consistente de idiomas soportados;
- contexto editorial para la comunidad hispana.

La plataforma necesitará una capa propia de localización y contenido editorial igualmente.

### 7.8 Ventajas

- API REST simple y rápida de probar.
- Filtros directos muy útiles para el MVP.
- Amplio catálogo e imágenes.
- Incluye tiendas, requisitos, vídeos y ratings que pueden ser útiles en fases posteriores.
- Menor curva de entrada que IGDB.

### 7.9 Inconvenientes

- Límite de 20.000 solicitudes/mes en el plan gratuito publicado.
- Plan Business de 149 USD/mes y solo 50.000 solicitudes/mes.
- Contradicción entre pricing y términos comerciales en la misma página.
- Atribución y backlink exigidos en cada página que use datos o imágenes.
- Prohibición de redistribución.
- Cadena de derechos de imágenes no garantizada de forma absoluta.
- Menor soporte visible para idiomas y localizaciones.
- Posible tentación de acoplar la ficha a campos de conveniencia como Metacritic.

## 8. Comparativa ponderada

Puntuación de 1 a 5, donde 5 representa el mejor encaje. Las puntuaciones son una evaluación técnica del spike, no datos publicados por los proveedores.

| Criterio | Peso | IGDB | RAWG | Comentario |
|---|---:|---:|---:|---|
| Cobertura funcional del MVP | 25% | 5,0 | 4,5 | Ambos cubren el recorrido; IGDB modela más relaciones y dimensiones |
| Lanzamientos y frescura | 15% | 5,0 | 4,0 | IGDB destaca en fechas por plataforma/región, `updated_at` y webhooks |
| Simplicidad de integración | 10% | 3,0 | 5,0 | RAWG usa REST y API key; IGDB requiere OAuth y APICalypse |
| Claridad legal/comercial | 20% | 3,0 | 2,0 | Ambos requieren confirmación; RAWG publica condiciones contradictorias |
| Español/localización | 10% | 3,0 | 2,0 | IGDB aporta localizaciones e idiomas, aunque no descripciones españolas garantizadas |
| Escalabilidad y operación | 10% | 4,5 | 3,0 | IGDB favorece sync local y no publica cap mensual; RAWG limita requests por plan |
| Coste durante el MVP | 10% | 4,5 | 4,0 | Ambos permiten una prueba gratuita no comercial; RAWG tiene salto comercial explícito |
| **Resultado ponderado** | **100%** | **82/100** | **69,5/100** | IGDB es el mejor candidato para el primer PoC |

## 9. Arquitectura propuesta para no acoplar el dominio

El Product Brief exige que el modelo externo no dicte el modelo interno. La integración debe residir en un adaptador dentro del monolito modular inicial.

```text
Frontend
   |
VideoGame Platform API
   |
Application use cases
   |
GameCatalogProvider port
   |
+--------------------------+
| IGDB adapter             |
| RAWG adapter (opcional)  |
+--------------------------+
   |
External provider API
```

### 9.1 Puerto de aplicación sugerido

```java
public interface GameCatalogProvider {

    List<ProviderRelease> findReleases(
        LocalDate from,
        LocalDate to,
        Set<PlatformRef> platforms
    );

    List<ProviderGameSummary> searchGames(String query, int limit);

    Optional<ProviderGameDetails> getGame(ProviderGameId providerId);

    List<ProviderChange> findChanges(Instant updatedAfter, int limit);
}
```

No todos los proveedores tienen que implementar internamente cada operación de la misma manera. El puerto expresa necesidades del producto, no endpoints externos.

### 9.2 Modelo canónico mínimo

```text
Game
- internalId
- canonicalTitle
- slug
- editorialSummaryEs        // propiedad de la plataforma
- sourceSummary
- sourceSummaryLanguage
- coverAsset
- genres[]
- companies[]
- releases[]
- supportedLanguages[]
- externalReferences[]
- provenance
- syncStatus

Release
- platform
- region
- releaseDate
- datePrecision
- status
- providerUpdatedAt

ExternalReference
- provider
- providerId
- providerUrl
```

### 9.3 Reglas de diseño

- Generar un identificador interno independiente.
- Mantener una tabla `external_game_reference` por proveedor.
- Registrar procedencia por campo cuando sea relevante.
- No mezclar rating interno de usuarios con ratings del proveedor.
- Mantener las imágenes como referencias externas inicialmente; no copiarlas a almacenamiento propio sin confirmar licencia.
- Persistir `provider_updated_at`, `last_synced_at`, `sync_status` y errores de sincronización.
- Aplicar timeouts, retry con backoff, circuit breaker y rate limiting.
- No bloquear la ficha pública si el proveedor está caído; servir la última versión sincronizada.
- Añadir contract tests con fixtures reales anonimizadas o permitidas.
- No crear un microservicio de integración en el MVP: un módulo y un worker programado son suficientes.

## 10. PoC autenticado recomendado

### 10.1 Orden

1. Obtener credenciales no comerciales de IGDB.
2. Ejecutar la muestra y documentar resultados.
3. Contactar con IGDB para confirmar condiciones del posible producto público/comercial.
4. Repetir una muestra reducida con RAWG solamente si IGDB falla en cobertura, calidad o condiciones.

### 10.2 Muestra de control congelada

La PoC debe usar los 60 casos versionados en
[`igdb-poc-sample.csv`](igdb-poc-sample.csv), congelados el 24 de julio de
2026 antes de ejecutar llamadas autenticadas:

- 10 lanzamientos recientes de PC, PlayStation, Xbox y Nintendo;
- 10 próximos lanzamientos;
- 10 títulos españoles o con nombre regionalizado;
- 10 juegos indie poco conocidos;
- 10 juegos antiguos con múltiples versiones o plataformas;
- 5 DLC/expansiones para comprobar clasificación;
- 5 juegos retrasados o con fecha imprecisa cuando existan casos conocidos.

Los campos `expected_*` son expectativas verificables tomadas de la evidencia
oficial enlazada, no datos observados de IGDB. Un valor vacío significa que la
muestra no afirma ese atributo. `expected_date_precision` permite distinguir
fechas de día, año o desconocidas, y `criticality` vincula cada caso con la
clasificación bloqueante o no bloqueante del gate.

### 10.3 Casos de prueba

| Caso | Evidencia esperada |
|---|---|
| Buscar por título exacto | Resultado correcto y sin duplicados inesperados |
| Buscar por título alternativo | El juego puede localizarse mediante nombre regional o alternativo |
| Lanzamientos de una semana | Fechas, plataformas y regiones coherentes |
| Próximos lanzamientos | Orden cronológico y ausencia de títulos cancelados como lanzamientos normales |
| Ficha básica | Título, cover, summary, géneros, compañías y plataformas |
| Idioma español | Diferenciar interfaz/ficha en español de idioma soportado por el juego |
| Cambio incremental | `updated_at` o mecanismo equivalente permite recuperar modificaciones |
| Rate limiting | El cliente respeta el límite sin 429 durante carga controlada |
| Fallo externo | Se conserva y sirve el último dato sincronizado |
| Atribución | La UI propuesta cumple los requisitos visibles del proveedor |

### 10.4 Métricas de aceptación confirmadas

- **Estado:** Aprobadas para ejecución
- **Decision owner:** Ruben Hernandez
- **Fecha de decisión:** 2026-07-24
- **Muestra aplicable:** [`igdb-poc-sample.csv`](igdb-poc-sample.csv)
- **Regla de decisión:** `PASS` / `CONDITIONAL PASS` / `FAIL`

Los umbrales se fijan antes de observar resultados para evitar adaptar el
criterio a la respuesta del proveedor.

#### Gate de datos

| Métrica | Umbral confirmado | Clasificación |
|---|---:|---|
| Búsqueda por título exacto | ≥ 95% encontrado correctamente | Bloqueante |
| Búsqueda por título alternativo o localizado | ≥ 80% del subconjunto aplicable | Limitación aceptable |
| Registros con `providerId`, procedencia y fecha de sincronización | 100% | Bloqueante |
| Juegos con plataforma correctamente identificada | ≥ 95% | Bloqueante |
| Lanzamientos con fecha o precisión correctamente representada | ≥ 90% | Bloqueante |
| Lanzamientos con región correctamente representada o marcada como desconocida | ≥ 85% | Bloqueante |
| Fichas con portada utilizable | ≥ 90% | No bloqueante |
| Fichas con género identificable | ≥ 90% | No bloqueante |
| Fichas con developer o publisher identificable | ≥ 85% | No bloqueante |
| Cancelados o retrasados mostrados como lanzamientos normales | 0 | Bloqueante |
| DLC, expansiones, ports o remasters fusionados silenciosamente | 0 | Bloqueante |
| Duplicados inesperados en resultados normales | ≤ 5% | Bloqueante |

La disponibilidad de `summary` en español no es un gate: el Product Brief
establece una capa editorial propia y no debe depender del proveedor para
resolverla.

#### Gate técnico y operativo

| Métrica | Umbral confirmado |
|---|---:|
| Secretos en Git, frontend, resultados o logs | 0 |
| Llamadas del navegador directamente a IGDB | 0 |
| Límite configurado en la PoC | Máximo 3 requests/segundo |
| Respuestas `429` durante carga controlada | 0 |
| Peticiones correctas tras retry limitado | ≥ 99% |
| Registros sincronizados que pueden leerse sin conexión a IGDB | 100% |
| Errores de normalización silenciosos | 0 |
| Request count, latencia y errores registrados | 100% de ejecuciones |

La latencia p95 debe medirse, pero no es bloqueante en esta fase: una ejecución
local no permite fijar un SLA representativo.

#### Interpretación del resultado

- **`PASS`:** se cumplen todos los criterios bloqueantes, los no bloqueantes
  quedan dentro del umbral, no existe dependencia crítica de textos en español
  y se demuestra sincronización con lectura local.
- **`CONDITIONAL PASS`:** se cumplen todos los criterios bloqueantes y los
  únicos fallos afectan a portada, compañía, género o título alternativo; cada
  limitación queda declarada y mitigada en el MVP.
- **`FAIL`:** falla cualquier criterio bloqueante; los duplicados o tipos no
  pueden controlarse; la solución necesita consultar IGDB en cada visita; no
  respeta el rate limit; o las condiciones contractuales o de imágenes resultan
  incompatibles.

## 11. Riesgos y mitigaciones

| Riesgo | Probabilidad | Impacto | Mitigación inicial |
|---|---|---|---|
| Condiciones comerciales ambiguas | Alta | Alta | Confirmación escrita antes de producción o monetización |
| Imágenes sin derechos suficientemente claros | Media-alta | Alta | Enlazar según términos, registrar procedencia y evitar copia hasta revisión |
| Contenido principalmente en inglés | Alta | Media-alta | Capa editorial propia en español y locale explícito |
| Duplicados entre versiones/ediciones | Alta | Media | Reglas de canonicalización y revisión de `game_type`/parent relationships |
| Fechas futuras cambiantes | Alta | Media | Reconciliar ventana futura de forma periódica |
| Caída o throttling del proveedor | Media | Media | Persistencia local, retry, rate limit, circuit breaker y datos stale permitidos |
| Cambio de esquema | Media | Media | Contract tests, mapeo defensivo y monitorización de campos deprecados |
| Acoplamiento a ratings externos | Media | Alta | Mantenerlos fuera del MVP y separados del rating interno |
| Coste por volumen | Media | Media-alta | Sync incremental, cache, presupuestos de requests y alertas |

## 12. Decisión propuesta

### Adoptar provisionalmente

- **Proveedor candidato principal:** IGDB.
- **Tipo de decisión:** reversible hasta completar PoC autenticado y validación contractual.
- **Uso inicial:** catálogo, búsqueda, ficha básica y lanzamientos.
- **No usar inicialmente:** ratings externos, críticas profesionales, Metacritic, vídeos y copia propia de imágenes.

### Condiciones para aprobar IGDB

- Cobertura suficiente en la muestra.
- Fechas por plataforma y región consistentes.
- Aceptación de almacenamiento/cache local.
- Condiciones comerciales y atribución confirmadas por escrito.
- Uso de portadas e imágenes compatible con la UI prevista.
- Integración incremental viable dentro de 4 solicitudes/segundo.
- Ausencia de dependencia crítica de textos en español proporcionados por el proveedor.

### Condiciones para cambiar a RAWG

- IGDB no cubre adecuadamente títulos o lanzamientos relevantes.
- La partnership o atribución de IGDB resulta incompatible.
- El coste de complejidad de APICalypse/OAuth no se justifica tras la prueba.
- RAWG aclara por escrito condiciones comerciales, imágenes, almacenamiento y redistribución.

## 13. Respuestas del spike

| Pregunta | Respuesta |
|---|---|
| ¿Hay proveedores viables? | Sí: IGDB y RAWG pueden cubrir documentalmente el recorrido aprobado del learning MVP |
| ¿Cuál probar primero? | IGDB |
| ¿Puede tomarse ya una decisión definitiva? | No; faltan credenciales, muestra real y confirmación contractual |
| ¿Cuál es más sencillo técnicamente? | RAWG |
| ¿Cuál ofrece mejor modelo para catálogo y lanzamientos? | IGDB |
| ¿Cuál tiene mayor riesgo comercial visible? | RAWG, por la contradicción entre pricing y términos |
| ¿Resuelven el contenido en español? | No; IGDB ayuda con localizaciones e idiomas, pero ninguno sustituye una capa editorial española |
| ¿Deben consultarse en tiempo real desde el frontend? | No; debe sincronizarse y servirse desde la plataforma |
| ¿Se deben importar ratings externos en el MVP? | No, hasta validar expresamente licencia y presentación |

## 14. Acciones siguientes

- [x] Confirmar y versionar los umbrales de aceptación antes de ejecutar la PoC.
- [x] Congelar la muestra de 60 casos con evidencia oficial.
- [ ] Crear cuenta/aplicación de Twitch para IGDB con credenciales de desarrollo.
- [ ] Añadir secretos únicamente al entorno local/secret manager.
- [ ] Crear una rama de spike con un cliente mínimo y fixtures.
- [ ] Ejecutar la muestra definida y guardar resultados reproducibles.
- [ ] Medir cobertura, duplicados, latencia, consumo de cuota y calidad de fechas.
- [ ] Contactar con `partner@igdb.com` para condiciones comerciales y atribución.
- [ ] Solicitar confirmación sobre derechos y almacenamiento de imágenes.
- [ ] Registrar la decisión final en un ADR únicamente después del PoC.
- [ ] Mantener RAWG como fallback y contactar con su soporte si IGDB no supera los gates.
- [ ] Actualizar `assumptions.md` y `open-questions.md` con la evidencia obtenida.

## 15. Fuentes consultadas

Consultadas el **23 de julio de 2026**.

### IGDB

- IGDB API documentation: <https://api-docs.igdb.com/>
- IGDB API overview: <https://www.igdb.com/api>
- Twitch Developer Service Agreement, enlazado por la documentación de IGDB: <https://www.twitch.tv/p/en/legal/developer-agreement/>

Aspectos verificados en la documentación oficial:

- autenticación OAuth mediante Twitch;
- límite de 4 solicitudes/segundo y 8 abiertas;
- máximo de 500 elementos por petición;
- endpoints de juegos, fechas, imágenes, localizaciones e idiomas;
- multi-query, webhooks y campos `updated_at`;
- política declarada de cache/almacenamiento;
- partnership, atribución y uso comercial sujeto a aclaración.

### RAWG

- RAWG API overview, pricing and terms: <https://rawg.io/apidocs>
- RAWG interactive API documentation: <https://api.rawg.io/docs/>

Aspectos verificados en la documentación oficial:

- autenticación por API key;
- filtros por fechas y plataformas;
- plan gratuito de hasta 20.000 solicitudes/mes;
- Business a 149 USD/mes y 50.000 solicitudes/mes;
- atribución y backlinks;
- prohibición de redistribución;
- contradicción entre pricing y el texto de términos comerciales;
- catálogo, imágenes, tiendas, ratings y datos de Metacritic anunciados.

## 16. Nivel de confianza

- **Encaje funcional:** alto, basado en documentación oficial.
- **Comparación técnica:** medio-alto, pendiente de llamadas autenticadas.
- **Calidad y cobertura real de datos:** media-baja, pendiente de muestra.
- **Condiciones legales/comerciales:** baja-media, pendiente de confirmación escrita.
- **Recomendación de orden de PoC:** alta.
