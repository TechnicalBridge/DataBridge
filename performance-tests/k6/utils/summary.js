// =============================================================================
//  Reporte de la corrida.
// =============================================================================
//  k6 imprime su resumen en la terminal. Este modulo agrega dos archivos por
//  corrida: el JSON completo (para comparar entre fechas) y un Markdown corto
//  (para pegar en un issue o en docs/rendimiento). El nombre y el directorio
//  salen de REPORTE_NOMBRE y REPORT_DIR.
// =============================================================================
import { REPORT_DIR, REPORTE_NOMBRE } from '../config/env.js';

const metricasDeInteres = /^(suite_|limitador_|volumen_|http_req_duration|http_req_failed|http_reqs|dropped_iterations|iterations|vus|checks)/;

function numero(valor, decimales = 2) {
  return typeof valor === 'number' && Number.isFinite(valor)
    ? valor.toFixed(decimales)
    : '—';
}

function textoMetrica(metrica) {
  const valores = metrica.values || {};
  switch (metrica.type) {
    case 'trend':
      return `p50 ${numero(valores['p(50)'])} ms · p95 ${numero(valores['p(95)'])} ms · `
        + `p99 ${numero(valores['p(99)'])} ms · max ${numero(valores.max)} ms`;
    case 'rate':
      return `${numero((valores.rate || 0) * 100, 2)} %`;
    case 'counter':
      return `${numero(valores.count, 0)} (${numero(valores.rate, 2)}/s)`;
    case 'gauge':
      return `${numero(valores.value)} (min ${numero(valores.min)} · max ${numero(valores.max)})`;
    default:
      return Object.entries(valores).map(([clave, valor]) => `${clave}=${numero(valor)}`).join(' · ');
  }
}

function umbrales(metrica) {
  if (!metrica.thresholds) return '';
  return Object.entries(metrica.thresholds)
    .map(([regla, resultado]) => `${resultado.ok ? 'OK' : 'FALLA'} ${regla}`)
    .join('<br>');
}

function filas(data) {
  return Object.entries(data.metrics)
    .filter(([nombre]) => metricasDeInteres.test(nombre))
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([nombre, metrica]) => ({
      nombre,
      tipo: metrica.type,
      valor: textoMetrica(metrica),
      umbrales: umbrales(metrica),
    }));
}

function markdown(data, nombre) {
  const duracion = data.state && data.state.testRunDurationMs
    ? data.state.testRunDurationMs / 1000
    : 0;
  const peticiones = data.metrics.suite_peticiones ? data.metrics.suite_peticiones.values.count : 0;
  const exitos = data.metrics.suite_exitos ? data.metrics.suite_exitos.values.count : 0;
  const errores = data.metrics.suite_errores ? data.metrics.suite_errores.values.count : 0;
  const tasa = data.metrics.suite_tasa_exito ? data.metrics.suite_tasa_exito.values.rate : 0;
  const throughput = duracion > 0 ? peticiones / duracion : 0;

  const lineas = [
    `# Reporte de rendimiento: ${nombre}`,
    '',
    `- Fecha: ${new Date().toISOString()}`,
    `- Duracion: ${numero(duracion, 1)} s`,
    `- Peticiones medidas: ${numero(peticiones, 0)}`,
    `- Exitos: ${numero(exitos, 0)}`,
    `- Errores: ${numero(errores, 0)}`,
    `- Tasa de exito: ${numero(tasa * 100, 2)} %`,
    `- Throughput observado: ${numero(throughput, 2)} peticiones/s`,
    '',
    '## Metricas',
    '',
    '| Metrica | Valor | Umbral |',
    '| --- | --- | --- |',
    ...filas(data).map((fila) => `| \`${fila.nombre}\` | ${fila.valor} | ${fila.umbrales || '—'} |`),
    '',
    '## Umbrales',
    '',
  ];

  const conUmbral = Object.entries(data.metrics).filter(([, metrica]) => metrica.thresholds);
  if (conUmbral.length === 0) {
    lineas.push('Sin umbrales configurados.');
  } else {
    for (const [nombreMetrica, metrica] of conUmbral) {
      for (const [regla, resultado] of Object.entries(metrica.thresholds)) {
        lineas.push(`- ${resultado.ok ? 'OK' : 'FALLA'} \`${nombreMetrica}\`: ${regla}`);
      }
    }
  }
  lineas.push('');
  return lineas.join('\n');
}

export function resumen(data, nombre = REPORTE_NOMBRE) {
  const marca = new Date().toISOString().replace(/[:.]/g, '-');
  const base = `${REPORT_DIR}/${nombre}-${marca}`;
  const salida = {
    [`${base}.json`]: JSON.stringify(data, null, 2),
    [`${base}.md`]: markdown(data, nombre),
    stdout: markdown(data, nombre),
  };
  return salida;
}
