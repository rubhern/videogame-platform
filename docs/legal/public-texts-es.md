# Gameómetro: borradores públicos de privacidad y condiciones

- **Status:** Draft for owner review; not approved for public product use.
- **Owner:** Rubén Hernández López; legal-name publication authorized; final text and publication approval pending.
- **Scope:** Future public, free, non-commercial, adult-only service; not a description of deployed production.

These Spanish texts are the canonical review copy, as explicitly requested by the
owner. The editorial checklist at the end is internal; it is not public-facing copy.
Do not copy this page into the product or deploy links to it. The
[publication checklist and integration plan](../development/data-protection.md#publication-and-product-integration)
own prerequisites, roles, provider evidence and proposed destinations. The
[legal research](../research/public-release-rights-and-privacy.md#9-legal-and-operational-increment-2026-10-17)
owns sources and uncertainty. No compliance certification is implied.

## Información básica antes de crear una cuenta

**Borrador para aprobación antes de mostrarlo en Keycloak.**

Responsable: Rubén Hernández López. Trataremos tu email, los datos
necesarios para proteger tu cuenta y tus puntuaciones para prestarte el servicio
que solicitas. La gestión de cuenta y puntuaciones se basa en la ejecución de las
condiciones de uso; la seguridad, en nuestro interés legítimo, según se explica en la política de privacidad. Puedes ejercer tus derechos en
[hola@gameometro.com](mailto:hola@gameometro.com). Consulta la política de privacidad
para conocer destinatarios, conservación, transferencias y todos tus derechos.

Crear una cuenta requiere tener al menos 18 años y aceptar las condiciones de uso.
La información de privacidad se facilita para que conozcas el tratamiento; no te
pedimos que consientas tratamientos opcionales para utilizar tu cuenta.

## Política de privacidad

**Borrador para el futuro lanzamiento; no está en vigor.**

### Responsable y contacto

El responsable del tratamiento es Rubén Hernández López, persona física y titular
de Gameómetro. Puedes contactar y ejercer tus derechos en
[hola@gameometro.com](mailto:hola@gameometro.com).

### Datos, finalidades y bases jurídicas

Puedes consultar el catálogo sin crear una cuenta. Al visitar la web, tu dispositivo
transmite los datos técnicos necesarios para establecer la conexión, incluida la
dirección IP. Las imágenes del catálogo se solicitan directamente al CDN de IGDB,
como explicamos más abajo.

Para crear y utilizar tu cuenta tratamos tu email, su estado de verificación,
identificadores internos, fechas de la cuenta y la credencial protegida mediante un
resumen criptográfico de la contraseña. No pedimos nombre, apellidos, fecha de
nacimiento ni documento de identidad al registrarte. Debes verificar el email antes
de usar la cuenta. El servicio está dirigido a mayores de 18 años, según tu
declaración. Sin el email y los datos de autenticación no podemos ofrecerte una
cuenta; puedes seguir consultando el catálogo.

Guardamos tus puntuaciones, el juego al que corresponden y las fechas de creación y
modificación. Tu lista personal no se publica. Cada puntuación contribuye a la media,
el número de votos y la distribución pública del juego, sin mostrar tu identidad.
No prometemos anonimato absoluto: las estadísticas de grupos pequeños pueden permitir
inferencias. No hay perfiles públicos, publicidad ni venta de datos personales.

La gestión de la cuenta, verificación, recuperación y puntuaciones se basa en la
prestación del servicio que solicitas al aceptar las condiciones (artículo 6.1.b
RGPD). Las sesiones incluyen identificadores, fechas de acceso, dirección IP y datos
del navegador o dispositivo en Keycloak; el BFF mantiene la sesión y sus credenciales
en el servidor. Se utilizan para autenticarte y evitar usos no autorizados.

La seguridad, prevención de abusos y recuperación mediante copias se basan en el
interés legítimo de proteger el servicio y sus usuarios (artículo 6.1.f RGPD), con
acceso restringido y minimización. No se realizan decisiones
individuales automatizadas con efectos jurídicos o similares ni perfiles comerciales.
Las protecciones automáticas pueden bloquear temporalmente intentos de acceso;
puedes pedir asistencia por email.

Si nos escribes, tratamos tu dirección y el contenido necesario para responder.
Las solicitudes de derechos y la gestión de brechas se atienden para cumplir las
obligaciones de protección de datos (artículo 6.1.c RGPD). No envíes contraseñas,
códigos de recuperación ni documentación personal innecesaria.

### Destinatarios y transferencias internacionales

La identidad se gestiona con Keycloak y las puntuaciones con PostgreSQL,
alojados bajo el control del titular. Los proyectos que desarrollan ese software no
reciben tus datos simplemente por usar sus programas. Los proveedores del servicio
son netcup para el alojamiento, OVHcloud para el dominio y el correo de contacto, y
Mailjet para el envío de mensajes exclusivamente transaccionales de verificación y
recuperación. Estos mensajes incluyen tu dirección, el contenido necesario y datos
de entrega; no son boletines ni publicidad.

Las copias cifradas y la información legal restringida se guardan en equipos y
soportes administrados por el responsable, separados del servidor de producción.
No se utilizan para elaborar perfiles ni para seguir prestando el servicio con datos
que deban permanecer bloqueados. Los proveedores pueden tratar datos para sus propias
obligaciones contractuales y legales, según su función.

Mailjet forma parte de Sinch, cuya información contractual contempla subencargados
fuera del Espacio Económico Europeo, incluido soporte en Estados Unidos. La ubicación
europea de un centro de datos no significa que todos los accesos se produzcan allí.
Puedes solicitar información sobre los destinatarios y las garantías de transferencia
aplicables a tu caso en hola@gameometro.com y consultar la
[información de protección de datos de Sinch](https://sinch.com/legal/terms-and-conditions/other-sinch-terms-conditions/data-processing-agreement/).

Al cargar portadas e imágenes, tu navegador conecta directamente con
`images.igdb.com`, servido mediante una red de distribución de contenidos. Esto
comunica tu IP, datos del navegador y el origen `gameometro.com`; no enviamos al CDN
tu email ni tus puntuaciones. Twitch informa de tratamiento de datos en Estados
Unidos en su [política de privacidad](https://legal.twitch.com/en/legal/privacy-notice/).
La información sobre estas conexiones forma parte de tu derecho de acceso y puedes
plantear una oposición al tratamiento en el contacto indicado.

No se venden tus datos ni se comunican a otros destinatarios salvo las necesidades
indicadas o una obligación legal. No solicitamos tu consentimiento para publicidad
ni para tratamientos comerciales ajenos al servicio.

### Conservación

Las cuentas verificadas y tus puntuaciones se conservan hasta que las elimines o
solicites su supresión, salvo una obligación legal aplicable. La inactividad no provoca
el borrado de una cuenta verificada. Puedes borrar cada puntuación desde
el producto. Para eliminar la cuenta y todas sus puntuaciones, escribe al contacto
anterior. Si no verificas el email, la cuenta se elimina al alcanzar 30 días desde su creación
en la siguiente ejecución periódica de limpieza. Puedes volver a registrarte después;
los enlaces de la cuenta eliminada dejarán de funcionar.

Las sesiones tienen un límite de 30 minutos de inactividad y 8 horas de duración
máxima. Los logs del servicio tendrán una conservación máxima de 7 días. Las copias
de seguridad estarán cifradas y caducarán como máximo a los 35 días, incluidas las
copias externas. Una restauración debe reaplicar los borrados antes de reabrir.
Estos límites corresponden a la operación de Gameómetro. Mailjet publica una conservación de 90 días para estadísticas y campañas del plan
Free, y mantiene contactos y plantillas durante la vida de la cuenta. No creamos
listas de contactos para publicidad. Ese plazo no equivale al de todos los datos de
entrega SMTP o del CDN; puedes pedir los plazos o criterios aplicables en el contacto
indicado.

El registro mínimo para impedir que una copia restaure datos borrados se conserva
mientras existan copias capaces de recuperarlos, al menos 35 días tras completar el
borrado; los borrados incompletos requieren resolución. El justificante de supresión
y la documentación necesaria para acreditar la atención
de derechos se conservan por separado del archivo de datos legalmente bloqueados y
del registro técnico de borrados. Los datos que deban bloquearse permanecen fuera del
uso ordinario durante un plazo inicial de tres años desde su bloqueo, con revisión
cada doce meses. Si una responsabilidad u obligación aplicable exige más tiempo, se
conservan únicamente los datos necesarios durante ese período, de forma documentada.
Después se destruyen. En los casos ordinarios, el archivo bloqueado conserva solo
los datos básicos necesarios de identidad y de la operación; no conserva tus
puntuaciones individuales. Ante una reclamación o responsabilidad concreta, solo se
reservan los datos adicionales pertinentes con su fundamento y plazo. Los tres años
no son un plazo legal universal ni autorizan a
conservar indiscriminadamente información personal.

### Derechos y reclamaciones

Puedes solicitar acceso, rectificación, supresión, limitación, oposición y portabilidad
cuando procedan, escribiendo a [hola@gameometro.com](mailto:hola@gameometro.com).
Indica qué necesitas y el email de tu cuenta. Verificaremos tu identidad con medios
proporcionados; no pedimos una copia del DNI por defecto. Si has perdido acceso al
email, indícalo sin adjuntar documentación inicialmente. No entregaremos un export a
una dirección alternativa sin verificar de forma suficiente su titularidad.

Puedes consultar y modificar tus puntuaciones en «Mis puntuaciones». El cambio de
email y la eliminación completa de la cuenta se gestionan administrativamente. El
acceso puede incluir información de tus sesiones e IP; la exportación de datos se
facilita en JSON. No incluye contraseñas ni secretos de autenticación.

Responderemos sin dilación indebida y, como máximo, en un mes desde la recepción.
Si la complejidad o el número de solicitudes lo requieren, podemos ampliar hasta dos
meses adicionales, informándote dentro del primer mes del motivo. El ejercicio es
gratuito, salvo las excepciones legales de solicitudes manifiestamente infundadas o
excesivas, que deben justificarse. Puedes reclamar ante la
[Agencia Española de Protección de Datos](https://www.aepd.es/), especialmente si no
recibes una respuesta satisfactoria. La aceptación de las condiciones no supone
renunciar a tus derechos.

## Condiciones de uso

**Borrador para el futuro lanzamiento; no está en vigor.**

Gameómetro es un proyecto personal, gratuito y sin actividad económica gestionado por
Rubén Hernández López, para descubrir videojuegos y conservar puntuaciones personales.
No incluye anuncios, afiliación, patrocinios, donaciones, ventas ni promoción profesional.
El contacto es [hola@gameometro.com](mailto:hola@gameometro.com).

Para registrarte debes tener 18 años o más, declarar esa circunstancia, aceptar
estas condiciones y verificar tu email. No aportes datos de otra persona. Protege tu
contraseña y avísanos si sospechas un acceso indebido. La recuperación de contraseña
se realiza mediante los mensajes del proveedor de identidad; nunca te pediremos tu
contraseña por email.

Puedes puntuar los juegos disponibles que admitan valoración, consultar, cambiar o
borrar tus puntuaciones. Se permite una puntuación activa de 1 a 10 por cuenta y
juego. Tu puntuación personal y los resultados agregados se presentan por separado.
Las puntuaciones externas de IGDB se identifican como tales y no se mezclan con las
de Gameómetro. No publiques material ilegal ni intentes acceder a cuentas ajenas,
eludir protecciones o perjudicar la disponibilidad del servicio.

El catálogo procede de fuentes externas, puede contener errores y no garantiza
cobertura completa ni fechas definitivas. Los resúmenes traducidos automáticamente
se identifican con su procedencia. Las imágenes y contenidos de terceros pertenecen
a sus respectivos titulares; mostrarlos no concede al usuario derechos sobre ellos
ni implica respaldo de IGDB, Twitch o las editoras. Las observaciones sobre derechos
pueden enviarse al contacto anterior.

El servicio se mantiene con dedicación limitada, sin promesa comercial de
disponibilidad continua ni soporte inmediato. Puede interrumpirse por mantenimiento,
seguridad o incidencias de proveedores, incluidas reclamaciones sobre contenidos.
Podemos suspender temporalmente el acceso mientras se investiga y se decide una
solución. Esto no excluye obligaciones legales ni responsabilidades que no puedan
limitarse. Cuando sea posible, se informará de cambios relevantes y del cierre del
servicio con oportunidad de solicitar los datos.

Puedes dejar de usarlo en cualquier momento, borrar puntuaciones o solicitar la baja
y el borrado de tu cuenta por email. Las reglas de conservación y derechos figuran en
la política de privacidad. Ante abuso, se podrán aplicar restricciones proporcionadas;
puedes solicitar revisión por email. Los cambios sustanciales de estas condiciones
se comunicarán antes de aplicarse; si alteran lo aceptado se evaluará una nueva
aceptación. No se limita el acceso a las autoridades o tribunales que resulten
competentes según las normas aplicables.

## Aviso legal

**Borrador para aprobación del propietario.**

Titular y responsable de Gameómetro (`gameometro.com`): Rubén Hernández López. Contacto: [hola@gameometro.com](mailto:hola@gameometro.com).

El proyecto es personal, gratuito y no desarrolla actividad económica: no incorpora
anuncios, afiliación, patrocinios, donaciones, ventas ni promoción profesional.

Gameómetro ofrece gratuitamente información sobre videojuegos y gestión de
puntuaciones. Los contenidos propios y los de terceros conservan sus respectivos
derechos. Consulta las condiciones de uso, la política de privacidad y la información
sobre cookies del servicio. Para errores o reclamaciones utiliza el contacto indicado.

## Cookies y almacenamiento del navegador

**Borrador para el futuro lanzamiento; no está en vigor.**

Utilizamos cookies para el acceso que solicitas, su seguridad y el idioma de los
formularios. No utilizamos cookies de publicidad ni analítica. No añadimos un banner
de consentimiento para estas cookies necesarias. Si se incorporan usos opcionales,
se revisarán antes de activarlos.

| Cookie | Host | Finalidad | Duración |
|---|---|---|---|
| `__Host-vgp_session` | `gameometro.com` | Sesión del BFF; identificador opaco | Sesión del navegador; servidor: 30 min de inactividad, máximo 8 h |
| `AUTH_SESSION_ID` | `auth.gameometro.com` | Inicio de autenticación | Sesión del navegador |
| `KC_AUTH_SESSION_HASH` | `auth.gameometro.com` | Coordinación segura de la autenticación | 60 segundos |
| `KC_RESTART` | `auth.gameometro.com` | Reanudar autenticación o recuperación interrumpida | Sesión del navegador |
| `KEYCLOAK_LOCALE` | `auth.gameometro.com` | Idioma de los formularios | Sesión del navegador |
| `KEYCLOAK_IDENTITY` | `auth.gameometro.com` | Identidad de la sesión de acceso único | Sesión del navegador |
| `KEYCLOAK_SESSION` | `auth.gameometro.com` | Estado de la sesión de acceso único | Persistente: máximo 8 horas |

Todas utilizan `Secure` y sin atributo `Domain`. Las cookies de Keycloak
se limitan a `/realms/gameometro/`; la del BFF, a `/`.
`KC_AUTH_SESSION_HASH` y `KEYCLOAK_SESSION` son legibles por scripts del proveedor de
identidad; las restantes usan `HttpOnly`. La cookie del BFF usa `SameSite=Lax` y las
de Keycloak, `SameSite=None`. Son del mismo sitio, aunque sus hosts sean distintos.

La aplicación no necesita crear cookies para consultar el catálogo sin identificarte.
No utiliza `localStorage`, `sessionStorage` ni IndexedDB para los recorridos de cuenta. Los tokens
OAuth se mantienen en el servidor; el cierre de sesión puede incluir un ID token en
la redirección al proveedor de identidad, sin almacenarlo de forma persistente en el
navegador. Las conexiones con el CDN de imágenes se explican en la política de
privacidad; esta información no las convierte en cookies de autenticación.

Puedes bloquear o borrar cookies desde los ajustes del navegador; hacerlo puede
impedir el registro, el acceso o la recuperación. La cookie de sesión del BFF y las
de identidad autenticada se eliminan al cerrar sesión; algunas cookies auxiliares
pueden permanecer hasta cerrar el navegador. La restauración de sesiones del navegador
puede conservar cookies de sesión, pero no prolonga los límites del servidor.

## Mensaje de confirmación de solicitud de derechos

**Plantilla administrativa; adaptar a la solicitud verificada.**

Hemos recibido tu solicitud el [fecha] y le hemos asignado la referencia [referencia].
Responderemos sin dilación indebida y dentro del plazo legal de un mes. Si necesitamos
información adicional para confirmar tu identidad, te pediremos solo la necesaria.
No nos envíes tu contraseña ni códigos de acceso. Puedes responder a este mensaje o
escribir a hola@gameometro.com. La recepción no significa que ya hayamos completado
la operación solicitada.

## Internal editorial release checklist

This section is not part of the public pages. Legal-name publication and the initial
non-economic/LSSI interpretation are owner decisions, not pending identity questions.
The foundation and reopening condition for omitting domicile/NIF belong in the
internal record/research, not a debate in the public notice.
Mailjet commercial eligibility is an internally accepted contractual risk, never
public copy. The supplier paragraphs describe the approved future topology, not a
claim that public production is deployed. Before publication:

- Approve this wording/version and effective date. C design is owner-approved
  conditionally on final text/provider/operational verification; revised A/B
  are now owner-authorized and prepared locally under the
  [data-protection record](../development/data-protection.md#phase-1-design-for-approval).
- Confirm actual OVHcloud receiving-mail product, entities/locations and applicable
  DPAs; replace generic supplier-retention language with verified periods or usable
  criteria. Determine actual Mailjet and CDN transfer roles/mechanisms and add the
  necessary concrete information to the transfer paragraphs. A link to a supplier's
  privacy policy and an email enquiry route do not establish Article 13 completeness
  or lawful transfers. No automatic all-EU/DPF/SCC assurance is approved here.
- Confirm the field-level legal-blocking/hold schedule and separate minimised
  administrative-proof retention. Complete CI and deployed acceptance of cleanup/archive before presenting
  these safeguards as operating facts; test actual cookies on the final
  hosts, including necessity/purpose of persistent Keycloak cookies and any actual
  CDN storage. The cookie text describes the intended verified release inventory;
  preparation observations alone are not its final production proof. No extra public paragraph about internal supplier eligibility discussions.
- Publish only the approved public sections through the product integration; never
  serve this editorial checklist, the internal register or operating storage paths.
