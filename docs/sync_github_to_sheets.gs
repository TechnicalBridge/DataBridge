/**
 * ==============================================================================
 * DATABRIDGE - SINCRONIZACIÓN AUTOMÁTICA GITHUB A GOOGLE SHEETS
 * Carpeta Destino: https://drive.google.com/drive/folders/1DIXDweM1M8X2dlM_SYIuLXR6FpH_rjJa
 * ==============================================================================
 *
 * INSTRUCCIONES DE INSTALACIÓN EN GOOGLE DRIVE:
 * 1. Abre tu Google Sheet dentro de la carpeta de Drive o sube el archivo generado.
 * 2. Ve al menú superior: Extensiones -> Apps Script.
 * 3. Borra el código existente y pega todo este archivo.
 * 4. (Opcional) Si tus repositorios fueran privados, añade un GitHub Personal Access Token
 *    en GITHUB_TOKEN (para repos públicos funciona sin token).
 * 5. Haz clic en el botón "Ejecutar" en la función 'sincronizarBacklogCompleto'.
 * 6. Para sincronización constante/automática:
 *    - En Apps Script ve al ícono de reloj (Activadores / Triggers).
 *    - Haz clic en "Añadir activador".
 *    - Elige 'sincronizarBacklogCompleto', fuente de eventos 'Basado en tiempo',
 *      temporizador por horas (ej. cada 1 hora o cada 30 minutos).
 * 7. Para sincronización a tiempo real (Webhook):
 *    - Publica como Aplicación Web (Deploy -> Nueva implementación -> Tipo: App Web).
 *    - Pega la URL resultante en GitHub: Repo -> Settings -> Webhooks (Eventos: Issues).
 */

// ---- CONFIGURACIÓN ----
const GITHUB_ORGS_REPOS = [
  "TechnicalBridge/TB_web",
  "TechnicalBridge/Technical-Bridge"
];

// Opcional: Solo necesario si se supera la tasa de 60 peticiones/hora de GitHub pública
const GITHUB_TOKEN = ""; // Ej: "ghp_xxxxxxxxxxxx"

// MAPEO DE USUARIOS A NOMBRES REALES
const MAPEO_USUARIOS = {
  "4mnesia": "Flavio Henriquez",
  "H4desDevs": "Flavio Henriquez",
  "Martin6k": "Martin Gutierrez",
  "martinkuruzg-hue": "Martin Gutierrez",
  "pedcampos": "Pedro Campos",
  "pedcamp": "Pedro Campos",
  "es-main77": "Esteban Maino"
};

/**
 * Menú personalizado en Google Sheets
 */
function onOpen() {
  const ui = SpreadsheetApp.getUi();
  ui.createMenu('⚡ DataBridge Sync')
    .addItem('🔄 Sincronizar Backlog Ahora', 'sincronizarBacklogCompleto')
    .addItem('⏰ Activar Sincronización Automática (Cada 1 hora)', 'crearActivadorAutomatico')
    .addToUi();
}

/**
 * Función principal de sincronización
 */
function sincronizarBacklogCompleto() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let wsBacklog = ss.getSheetByName("Product Backlog");
  if (!wsBacklog) {
    wsBacklog = ss.insertSheet("Product Backlog");
  }

  // 1. Obtener todos los issues de los repositorios
  const todosLosIssues = [];
  for (const repo of GITHUB_ORGS_REPOS) {
    const issues = obtenerIssuesDeRepo(repo);
    todosLosIssues.push(...issues);
  }

  // Ordenar: Abiertos primero, luego por número descendente
  todosLosIssues.sort((a, b) => {
    if (a.state === 'open' && b.state !== 'open') return -1;
    if (a.state !== 'open' && b.state === 'open') return 1;
    return b.number - a.number;
  });

  // 2. Preparar encabezados y filas
  const encabezados = [
    "ID", "Tipo", "Módulo / Componente", "Título de la Tarea / Requerimiento",
    "Responsable(s)", "Prioridad", "Estado de Avance", "Duración / Est.",
    "Recurso / Enlace", "Observaciones / Ajustes"
  ];

  const filas = [];
  const estadisticas = {
    estado: {},
    prioridad: {},
    responsable: {},
    modulo: {},
    total: 0
  };

  todosLosIssues.forEach((iss, index) => {
    const id = "PB-" + String(index + 1).padStart(2, '0');
    const tipo = inferirTipo(iss.title, iss.labels);
    const modulo = inferirModulo(iss.title, iss.labels);
    const titulo = iss.title.trim();
    const responsables = mapearResponsables(iss.assignees);
    const prioridad = inferirPrioridad(iss.title, iss.labels);
    const estado = (iss.state === "closed") ? "Completado" : (responsables !== "Sin asignar" ? "En curso" : "Listo");
    const duracion = tipo === "Épica" ? "XL" : (prioridad === "Alto" ? "M" : "S");
    const recurso = iss.html_url;
    const observaciones = `Issue #${iss.number} (${iss.state === 'open' ? 'Abierto' : 'Cerrado'}).`;

    filas.push([
      id, tipo, modulo, titulo, responsables, prioridad, estado, duracion, recurso, observaciones
    ]);

    // Métricas
    estadisticas.total++;
    estadisticas.estado[estado] = (estadisticas.estado[estado] || 0) + 1;
    estadisticas.prioridad[prioridad] = (estadisticas.prioridad[prioridad] || 0) + 1;
    estadisticas.responsable[responsables] = (estadisticas.responsable[responsables] || 0) + 1;
    estadisticas.modulo[modulo] = (estadisticas.modulo[modulo] || 0) + 1;
  });

  // 3. Escribir y estilizar Product Backlog
  wsBacklog.clearContents();
  wsBacklog.getRange(1, 1, 1, encabezados.length).setValues([encabezados]);
  
  if (filas.length > 0) {
    wsBacklog.getRange(2, 1, filas.length, encabezados.length).setValues(filas);
  }

  // Estilos de encabezado
  const rEncabezado = wsBacklog.getRange(1, 1, 1, encabezados.length);
  rEncabezado.setBackground("#1F2937")
             .setFontColor("#FFFFFF")
             .setFontFamily("Arial")
             .setFontSize(10)
             .setFontWeight("bold")
             .setHorizontalAlignment("center");

  wsBacklog.setFrozenRows(1);

  // 4. Actualizar Hoja de Métricas
  actualizarMetricas(ss, estadisticas);

  SpreadsheetApp.getActiveSpreadsheet().toast(`¡Sincronización completada! ${filas.length} tareas procesadas.`, "DataBridge Sync", 5);
}

/**
 * Consulta la API de GitHub para un repositorio
 */
function obtenerIssuesDeRepo(repo) {
  const url = `https://api.github.com/repos/${repo}/issues?state=all&per_page=100`;
  const headers = {
    "Accept": "application/vnd.github.v3+json",
    "User-Agent": "GoogleAppsScript-DataBridge"
  };
  if (GITHUB_TOKEN) {
    headers["Authorization"] = "token " + GITHUB_TOKEN;
  }

  try {
    const res = UrlFetchApp.fetch(url, { headers: headers, muteHttpExceptions: true });
    if (res.getResponseCode() === 200) {
      const data = JSON.parse(res.getContentText());
      return data.filter(item => !item.pull_request);
    }
  } catch (err) {
    Logger.log("Error al consultar " + repo + ": " + err);
  }
  return [];
}

/**
 * Mapea los nombres de usuario de GitHub a los nombres reales
 */
function mapearResponsables(assignees) {
  if (!assignees || assignees.length === 0) return "Sin asignar";
  const mapped = [];
  assignees.forEach(a => {
    const real = MAPEO_USUARIOS[a.login] || a.login;
    if (!mapped.includes(real)) {
      mapped.push(real);
    }
  });

  if (mapped.length >= 4) {
    return "Todo el equipo";
  }
  return mapped.join(" / ");
}

function inferirTipo(title, labels) {
  const tl = title.toLowerCase();
  const lbls = (labels || []).map(l => (l.name || "").toLowerCase());
  if (tl.startsWith("épica") || tl.startsWith("epica")) return "Épica";
  if (tl.startsWith("feat") || lbls.includes("enhancement")) return "Funcionalidad (Feat)";
  if (tl.startsWith("fix") || lbls.includes("bug")) return "Corrección (Fix)";
  if (tl.startsWith("chore")) return "Mantenimiento (Chore)";
  if (tl.startsWith("docs") || lbls.includes("documentation")) return "Documentación";
  if (tl.startsWith("security") || tl.includes("seguridad")) return "Seguridad";
  if (tl.startsWith("refactor")) return "Refactorización";
  if (tl.startsWith("ci") || lbls.includes("github_actions")) return "CI / DevOps";
  if (tl.includes("test")) return "QA / Testing";
  if (tl.includes("manual") || tl.includes("diseño") || tl.includes("requisitos") || tl.includes("evidencias")) return "Hito / Entregable";
  return "Tarea Técnica";
}

function inferirModulo(title, labels) {
  const tl = title.toLowerCase();
  if (tl.includes("portal") || tl.includes("frontend")) return "Portal Web";
  if (tl.includes("admin")) return "Portal Administrativo";
  if (tl.includes("ms-auth") || tl.includes("auth")) return "ms-auth";
  if (tl.includes("ms-debt") || tl.includes("debt")) return "ms-debt";
  if (tl.includes("ms-payments") || tl.includes("pasarela") || tl.includes("pago")) return "ms-payments";
  if (tl.includes("ms-ai") || tl.includes("asistente") || tl.includes("chat")) return "ms-ai";
  if (tl.includes("correo") || tl.includes("smtp") || tl.includes("brevo")) return "ms-auth / SMTP";
  if (tl.includes("docker")) return "DevOps / Docker";
  if (tl.includes("mysql") || tl.includes("base")) return "Base de Datos (MySQL)";
  if (tl.includes("vps") || tl.includes("cloud") || tl.includes("despliegue")) return "Cloud / DevOps";
  if (tl.includes("manual") || tl.includes("diseño") || tl.includes("requisitos") || tl.includes("evidencias") || tl.includes("matriz")) return "Gestión Proyecto APT";
  return "Core Backend (Transversal)";
}

function inferirPrioridad(title, labels) {
  const tl = title.toLowerCase();
  if (tl.includes("security") || tl.includes("épica") || tl.includes("desplegar") || tl.includes("vps")) return "Alto";
  if (tl.startsWith("chore") || tl.includes("baja")) return "Bajo";
  return "Medio";
}

/**
 * Actualiza la hoja de métricas del Backlog
 */
function actualizarMetricas(ss, stats) {
  let wsMet = ss.getSheetByName("Métricas del Backlog");
  if (!wsMet) {
    wsMet = ss.insertSheet("Métricas del Backlog");
  }
  wsMet.clearContents();

  wsMet.getRange("A1").setValue("DataBridge - Resumen Ejecutivo de Métricas del Product Backlog")
       .setFontFamily("Arial").setFontSize(13).setFontWeight("bold");

  let r = 3;

  function pintarTabla(titulo, cabecera1, datos) {
    wsMet.getRange(r, 1).setValue(titulo).setFontFamily("Arial").setFontSize(11).setFontWeight("bold");
    r++;
    wsMet.getRange(r, 1, 1, 3).setValues([[cabecera1, "Cantidad", "Porcentaje"]])
         .setBackground("#374151").setFontColor("#FFFFFF").setFontWeight("bold").setHorizontalAlignment("center");
    r++;

    const total = stats.total || 1;
    let suma = 0;
    const sorted = Object.entries(datos).sort((a, b) => b[1] - a[1]);

    sorted.forEach(([nombre, cant]) => {
      suma += cant;
      const pct = ((cant / total) * 100).toFixed(1) + "%";
      wsMet.getRange(r, 1, 1, 3).setValues([[nombre, cant, pct]]);
      wsMet.getRange(r, 2, 1, 2).setHorizontalAlignment("center");
      r++;
    });

    wsMet.getRange(r, 1, 1, 3).setValues([["TOTAL", suma, "100.0%"]]).setFontWeight("bold");
    wsMet.getRange(r, 2, 1, 2).setHorizontalAlignment("center");
    r += 3;
  }

  pintarTabla("Distribución por Estado de Avance", "Estado", stats.estado);
  pintarTabla("Distribución por Prioridad", "Prioridad", stats.prioridad);
  pintarTabla("Distribución por Responsable / Asignado", "Responsable", stats.responsable);
  pintarTabla("Distribución por Módulo / Componente", "Módulo / Componente", stats.modulo);

  wsMet.setColumnWidth(1, 280);
  wsMet.setColumnWidth(2, 120);
  wsMet.setColumnWidth(3, 120);
}

/**
 * Crea un activador automático para correr cada 1 hora
 */
function crearActivadorAutomatico() {
  const triggers = ScriptApp.getProjectTriggers();
  triggers.forEach(t => {
    if (t.getHandlerFunction() === 'sincronizarBacklogCompleto') {
      ScriptApp.deleteTrigger(t);
    }
  });

  ScriptApp.newTrigger('sincronizarBacklogCompleto')
    .timeBased()
    .everyHours(1)
    .create();

  SpreadsheetApp.getActiveSpreadsheet().toast("Activador programado: sincronización cada 1 hora.", "DataBridge Auto-Sync");
}

/**
 * Receptor de Webhook para tiempo real
 */
function doPost(e) {
  try {
    sincronizarBacklogCompleto();
    return ContentService.createTextOutput(JSON.stringify({ status: "success" })).setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({ status: "error", message: err.toString() })).setMimeType(ContentService.MimeType.JSON);
  }
}
