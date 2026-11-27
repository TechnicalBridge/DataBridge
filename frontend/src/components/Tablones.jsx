/**
 * El avance de una deuda como los tablones del puente de la portada (#546):
 * uno por cuota. Encendidos en verde los ya pagados, en terracota los vencidos
 * sin pagar, apagados los que faltan. Sin convenio no hay tablones: uno suelto
 * no dice nada (#557), y queda solo el texto.
 *
 * El dibujo es decorativo: lo que dice va siempre en `texto`, que es lo que se
 * lee. Al montarse, los pagados se encienden uno tras otro, como en la portada.
 *
 * @param estados un estado por tablon, en orden: "pagado", "vencido", "marcado" (el que se mira) o "".
 */
export default function Tablones({ estados, texto, chico = false, detenida = false, animar = true }) {
  const conTablones = estados.length > 1;
  if (!conTablones && !texto) return null;
  return (
    <div className={`tablas${chico ? " chico" : ""}${detenida ? " detenida" : ""}${animar ? " animada" : ""}`}>
      {conTablones ? (
        <div className="tablas-fila" style={{ "--n": estados.length }} aria-hidden="true">
          {estados.map((estado, i) => (
            <span key={i} className={estado ? `tabla ${estado}` : "tabla"} style={{ "--j": i }} />
          ))}
        </div>
      ) : null}
      {texto ? <p className="tablas-texto">{texto}</p> : null}
    </div>
  );
}

/** Los tablones de una deuda: cuantas cuotas, cuantas pagadas y cuantas vencidas. */
export function tablonesDe(d) {
  const enConvenio = d.conConvenio && Number(d.cuotasTotales) > 0;
  const total = enConvenio ? Number(d.cuotasTotales) : 1;
  const pagadas = d.estado === "paid" ? total : enConvenio ? Number(d.cuotasPagadas || 0) : 0;
  const vencidas = d.estado === "repacted" ? Number(d.cuotasVencidas || 0) : 0;
  return Array.from({ length: total }, (_, i) =>
    i < pagadas ? "pagado" : i < pagadas + vencidas ? "vencido" : "");
}

/** Lo que dicen los tablones de una deuda, en palabras. */
export function avanceEnPalabras(d) {
  const total = Number(d.cuotasTotales || 0);
  const pagadas = Number(d.cuotasPagadas || 0);
  switch (d.estado) {
    case "paid":
      return d.conConvenio && total ? `Pagada: las ${total} cuotas` : "Pagada";
    case "repacted": {
      const faltan = total - pagadas;
      const vencidas = Number(d.cuotasVencidas || 0);
      return `En convenio: pagaste ${pagadas} de ${total} cuotas, ${faltan === 1 ? "te falta 1" : `te faltan ${faltan}`}`
        + (vencidas ? ` (${vencidas === 1 ? "una vencida" : `${vencidas} vencidas`})` : "");
    }
    case "withdrawn":
      return "Retirada por el acreedor: no se cobrará";
    case "disputed":
      return "En revisión: mientras tanto no se cobra";
    default:
      return "Por pagar: todo de una vez o en cuotas";
  }
}
