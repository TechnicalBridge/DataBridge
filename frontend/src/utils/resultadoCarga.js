export function mensajesCarga(resultado, campana = "la campaña") {
  if (resultado.repetido) return ["Esa planilla ya estaba cargada: no se duplicó nada."];

  const conteos = new Map();
  for (const fila of resultado.resultados ?? []) {
    conteos.set(fila.resultado, (conteos.get(fila.resultado) ?? 0) + 1);
  }

  const mensajes = [];
  function agregar(cantidad, singular, plural) {
    if (cantidad > 0) mensajes.push(`${cantidad} ${cantidad === 1 ? singular : plural}.`);
  }

  agregar(conteos.get("registrada"), `deuda entró en ${campana}`, `deudas entraron en ${campana}`);
  agregar(conteos.get("actualizada"), `deuda se actualizó en ${campana}`, `deudas se actualizaron en ${campana}`);
  agregar(conteos.get("retirada"), "deuda salió de la cobranza", "deudas salieron de la cobranza");
  agregar(conteos.get("rechazada") ?? resultado.rechazadas, "deuda no entró", "deudas no entraron");
  agregar(conteos.get("sin_cambios"), "deuda quedó sin cambios", "deudas quedaron sin cambios");
  agregar(conteos.get("al_dia"), "cliente al día no se guardó", "clientes al día no se guardaron");
  return mensajes.length ? mensajes : ["La carga no produjo cambios."];
}
