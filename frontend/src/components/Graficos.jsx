import { useState } from "react";
import {
  Bar, BarChart, CartesianGrid, Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from "recharts";
import { dinero, ESTADO_DEUDA, montoCorto } from "../utils/formato";
import { useTema } from "../store/temaStore";

/**
 * Los colores de los graficos, por tema. Recharts pinta en SVG con colores
 * fijos y no lee las variables de CSS, asi que la paleta se repite aca: si
 * cambia index.css, cambia esto.
 */
const PALETA = {
  light: {
    activas: "#c8764b", enConvenio: "#d9a441", enRevision: "#b0443a", pagadas: "#3e7b4f", retiradas: "#c9bda8",
    barra: "#3e7b4f", texto: "#7c7266", grilla: "#ebe1cf", fondo: "#fffdf8", borde: "#dccfb8", tinta: "#2e2a24",
  },
  dark: {
    activas: "#e09a72", enConvenio: "#e7bd64", enRevision: "#f08a84", pagadas: "#7dbd8c", retiradas: "#57524a",
    barra: "#7dbd8c", texto: "#a59d90", grilla: "#2e2b27", fondo: "#1c1a17", borde: "#3d3933", tinta: "#efebe3",
  },
};

function usePaleta() {
  const tema = useTema((s) => s.tema);
  const c = PALETA[tema];
  const tooltip = {
    contentStyle: { background: c.fondo, border: `1px solid ${c.borde}`, borderRadius: 12, color: c.tinta },
    itemStyle: { color: c.tinta },
    labelStyle: { color: c.texto },
  };
  return { c, tooltip };
}

/**
 * El estado de la cartera: cuenta deudas, asi que no mezcla monedas. Cuenta lo mismo que los filtros de la
 * tabla, con sus nombres: el resumen junta las que estan en revision con las pendientes (#280).
 */
export function EstadoCartera({ resumen, cuentas }) {
  const { c, tooltip } = usePaleta();
  if (!resumen || !cuentas) return <div className="card esqueleto" style={{ minHeight: 300 }} />;
  const datos = [
    { clave: "activas", estado: "open" },
    { clave: "enConvenio", estado: "repacted" },
    { clave: "enRevision", estado: "disputed" },
    { clave: "pagadas", estado: "paid" },
    { clave: "retiradas", estado: "withdrawn" },
  ].map((d) => ({ ...d, nombre: ESTADO_DEUDA[d.estado], valor: cuentas[d.estado] || 0 })).filter((d) => d.valor > 0);

  return (
    <div className="card">
      <h3>Estado de la cartera</h3>
      {datos.length === 0 ? (
        <div className="empty">Sin deudas todavía.</div>
      ) : (
        <div style={{ height: 240 }}>
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie data={datos} dataKey="valor" nameKey="nombre" innerRadius={58} outerRadius={88} paddingAngle={3}
                   animationDuration={900}>
                {datos.map((d) => <Cell key={d.clave} fill={c[d.clave]} stroke="none" />)}
              </Pie>
              <Tooltip {...tooltip} formatter={(v, n) => [`${v} deuda(s)`, n]} />
              {/* Con el numero de cada estado: el color solo no dice cuantas son (#280). */}
              <Legend iconType="circle" wrapperStyle={{ fontSize: 13, color: c.tinta }}
                      formatter={(nombre, entrada) => (
                        <span style={{ color: c.tinta }}>{nombre} · {entrada.payload.valor}</span>
                      )} />
            </PieChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}

/**
 * Lo que entro cada dia. Una serie por moneda, con selector: pesos y UF en
 * el mismo eje darian barras que no se pueden comparar.
 */
export function RecuperadoPorDia({ resumen }) {
  const { c, tooltip } = usePaleta();
  const series = resumen?.recuperadoPorDia || {};
  const monedas = Object.keys(series);
  const [elegida, setElegida] = useState(null);
  const moneda = monedas.includes(elegida) ? elegida : monedas[0];
  //  El dia como en Chile, 05/10, y el total del periodo arriba (#280).
  const datos = (series[moneda] || []).map((p) => ({ dia: `${p.dia.slice(8, 10)}/${p.dia.slice(5, 7)}`, monto: Number(p.monto) }));
  const total = datos.reduce((suma, p) => suma + p.monto, 0);

  if (!resumen) return <div className="card esqueleto" style={{ minHeight: 300 }} />;

  return (
    <div className="card">
      <div className="card-cab">
        <div>
          <h3>Recuperado por día</h3>
          {monedas.length ? <span className="sub">{dinero(total, moneda)} en los últimos 30 días</span> : null}
        </div>
        {monedas.length > 1 ? (
          <div className="filters">
            {monedas.map((m) => (
              <button key={m} type="button" className={`chip ${m === moneda ? "on" : ""}`} onClick={() => setElegida(m)}>
                {m}
              </button>
            ))}
          </div>
        ) : null}
      </div>
      {monedas.length === 0 ? (
        <div className="empty">Sin pagos en los últimos 30 días.</div>
      ) : (
        <div style={{ height: 240 }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={datos}>
              <CartesianGrid stroke={c.grilla} vertical={false} />
              <XAxis dataKey="dia" stroke={c.texto} tick={{ fontSize: 10 }} interval={4} />
              <YAxis stroke={c.texto} tick={{ fontSize: 11 }} width={78} tickFormatter={(v) => montoCorto(v, moneda)} />
              <Tooltip {...tooltip} cursor={{ fill: c.grilla, opacity: 0.5 }}
                       formatter={(v) => [dinero(v, moneda), "Recuperado"]} />
              <Bar dataKey="monto" fill={c.barra} radius={[6, 6, 0, 0]} animationDuration={900} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}
