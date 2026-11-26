import {
  Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from "recharts";
import { dinero, ESTADO_DEUDA, montoCorto } from "../utils/formato";
import { usePaleta } from "./Graficos";

const numero = (n) => Number(n).toLocaleString("es-CL");

/**
 * Lo que entro cada dia de los ultimos 30, de todas las empresas y en pesos
 * (#544): la misma base que "Recuperado este mes". Los dias sin pagos vienen en
 * cero desde ms-debt.
 */
export function RecuperadoDiario({ puntos = [] }) {
  const { c, tooltip } = usePaleta();
  const datos = puntos.map((p) => ({ dia: `${p.dia.slice(8, 10)}/${p.dia.slice(5, 7)}`, pesos: Number(p.pesos) }));
  const total = datos.reduce((suma, p) => suma + p.pesos, 0);

  return (
    <div className="admin-grafico">
      <p className="admin-grafico-total"><b>{dinero(total)}</b> en los últimos 30 días</p>
      {total === 0 ? (
        <p className="admin-dato-estado">Sin pagos en los últimos 30 días.</p>
      ) : (
        <div className="admin-grafico-lienzo" role="img"
             aria-label={`Recuperado por día en los últimos 30 días: ${dinero(total)} en total`}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={datos} margin={{ top: 6, right: 4, bottom: 0, left: 0 }}>
              <CartesianGrid stroke={c.grilla} vertical={false} />
              <XAxis dataKey="dia" stroke={c.texto} tick={{ fontSize: 11 }} tickLine={false}
                     interval="preserveStartEnd" minTickGap={18} />
              <YAxis stroke={c.texto} tick={{ fontSize: 11 }} tickLine={false} axisLine={false} width={64}
                     tickFormatter={(v) => montoCorto(v)} />
              <Tooltip {...tooltip} cursor={{ fill: c.grilla, opacity: 0.5 }}
                       formatter={(v) => [dinero(v), "Recuperado"]} />
              <Bar dataKey="pesos" fill={c.barra} radius={[5, 5, 0, 0]} animationDuration={900} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}

const PASARELAS = [["webpay", "Webpay"], ["mercadopago", "Mercado Pago"], ["khipu", "Khipu"]];

/** Los pagos confirmados de cada pasarela, hoy y en el mes: el detalle que antes estaba en el inicio. */
export function PagosPorPasarela({ hoy = {}, mes = {} }) {
  const { c, tooltip } = usePaleta();
  const datos = PASARELAS.map(([clave, nombre]) => ({
    nombre,
    hoy: Number(hoy[clave] ?? 0),
    mes: Number(mes[clave] ?? 0),
  }));
  const resumen = datos.map((d) => `${d.nombre}: ${d.hoy} hoy y ${d.mes} este mes`).join("; ");

  return (
    <div className="admin-grafico">
      <div className="admin-grafico-lienzo admin-grafico-lienzo-bajo" role="img" aria-label={`Pagos por pasarela. ${resumen}`}>
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={datos} layout="vertical" margin={{ top: 0, right: 8, bottom: 0, left: 0 }} barGap={3}>
            <CartesianGrid stroke={c.grilla} horizontal={false} />
            <XAxis type="number" allowDecimals={false} stroke={c.texto} tick={{ fontSize: 11 }} tickLine={false} />
            <YAxis type="category" dataKey="nombre" stroke={c.texto} tick={{ fontSize: 12 }} tickLine={false}
                   axisLine={false} width={92} />
            <Tooltip {...tooltip} cursor={{ fill: c.grilla, opacity: 0.5 }}
                     formatter={(v, n) => [`${numero(v)} ${Number(v) === 1 ? "pago" : "pagos"}`, n]} />
            <Legend iconType="circle" iconSize={8}
                    formatter={(nombre) => <span style={{ color: c.tinta, fontSize: 12.5 }}>{nombre}</span>} />
            <Bar dataKey="mes" name="Este mes" fill={c.barra} radius={[0, 5, 5, 0]} animationDuration={900} />
            <Bar dataKey="hoy" name="Hoy" fill={c.enConvenio} radius={[0, 5, 5, 0]} animationDuration={900} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

/**
 * La cartera por estado en una dona, con los mismos colores de las tarjetas de
 * cada estado (sus variables de CSS). SVG a mano: cada tramo es un arco del
 * circulo con stroke-dasharray, asi el color sale del tema y no de una paleta aparte.
 */
export function CarteraDona({ deudas = {} }) {
  const estados = Object.keys(ESTADO_DEUDA).map((estado) => ({ estado, cantidad: Number(deudas[estado] ?? 0) }));
  const total = estados.reduce((suma, e) => suma + e.cantidad, 0);
  const radio = 42;
  const vuelta = 2 * Math.PI * radio;
  let recorrido = 0;

  return (
    <svg className="admin-dona" viewBox="0 0 100 100" role="img"
         aria-label={`Cartera por estado: ${estados.filter((e) => e.cantidad).map((e) => `${ESTADO_DEUDA[e.estado]} ${e.cantidad}`).join(", ") || "sin deudas"}`}>
      <circle className="admin-dona-fondo" cx="50" cy="50" r={radio} />
      {total > 0 && estados.filter((e) => e.cantidad > 0).map(({ estado, cantidad }, i) => {
        const largo = (cantidad / total) * vuelta;
        const tramo = (
          <circle key={estado} className={`admin-dona-tramo estado-${estado}`} cx="50" cy="50" r={radio}
                  strokeDasharray={`${largo} ${vuelta - largo}`} strokeDashoffset={-recorrido}
                  style={{ "--i": i }} />
        );
        recorrido += largo;
        return tramo;
      })}
    </svg>
  );
}
