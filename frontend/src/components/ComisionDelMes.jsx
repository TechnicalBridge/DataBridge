import { useEffect, useState } from "react";
import { comisionDelMes } from "../api/analitica";
import { dinero, porcentaje } from "../utils/formato";

/** El mes de hoy, en Chile, como 2026-10: lo que pide la API y lo que usa el selector. */
export function mesDeHoy() {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "America/Santiago", year: "numeric", month: "2-digit" })
    .format(new Date()).slice(0, 7);
}

/** 2026-10-01 -> "octubre de 2026" */
export function mesEnPalabras(fecha) {
  const [ano, mes] = String(fecha).split("-").map(Number);
  return new Date(ano, mes - 1, 1).toLocaleDateString("es-CL", { month: "long", year: "numeric" });
}

/** "Más de $1.000.000 y hasta $5.000.000" */
function tramo(t) {
  if (t.hasta == null) return `Más de ${dinero(t.desde)}`;
  return t.desde === 0 ? `Hasta ${dinero(t.hasta)}` : `Más de ${dinero(t.desde)} y hasta ${dinero(t.hasta)}`;
}

/**
 * El modelo de negocio, visto por la empresa (#200): lo que recuperó en el mes (G), su tramo (p), lo que le
 * paga a DataBridge (P = G · p) y lo que le queda (G − P). onDatos recibe la respuesta, para que Campañas
 * muestre lo que recuperó cada una.
 */
export default function ComisionDelMes({ onDatos }) {
  const [mes, setMes] = useState(mesDeHoy());
  const [datos, setDatos] = useState(null);
  const [error, setError] = useState("");
  const [cargando, setCargando] = useState(true);

  useEffect(() => {
    let vigente = true;
    setError("");
    setDatos(null);
    onDatos?.(null);
    setCargando(true);
    comisionDelMes(mes)
      .then((d) => {
        if (!vigente) return;
        setDatos(d);
        onDatos?.(d);
      })
      .catch((err) => vigente && setError(err.message))
      .finally(() => vigente && setCargando(false));
    return () => { vigente = false; };
  }, [mes, onDatos]);

  return (
    <section className="card aparece comision-del-mes" aria-labelledby="titulo-comision">
      <div className="card-cab">
        <h3 id="titulo-comision">Lo que pagas a DataBridge</h3>
        <label className="selector-mes">
          Mes{" "}
          <input type="month" value={mes} max={mesDeHoy()} onChange={(e) => e.target.value && setMes(e.target.value)} />
        </label>
      </div>
      <p className="hint" style={{ marginTop: -6 }}>
        Un porcentaje de lo que recuperas en el mes, sumando todas tus campañas: el tramo lo decide el total.
      </p>
      {error ? <div className="error">{error}</div> : null}
      {cargando ? <p className="hint" role="status">Cargando comisión…</p> : null}
      {datos ? (
        <>
          <div className="cifras-revision" aria-live="polite">
            <div className="cifra"><b>{dinero(datos.recuperado)}</b><span>recuperado en {mesEnPalabras(datos.mes)} · G</span></div>
            <div className="cifra"><b>{datos.recuperado ? porcentaje(datos.porcentaje) : "—"}</b><span>tu tramo · p(G)</span></div>
            <div className="cifra"><b>{dinero(datos.comision)}</b><span>para DataBridge · P = G · p</span></div>
            <div className="cifra entran"><b>{dinero(datos.paraLaEmpresa)}</b><span>para tu empresa · G − P</span></div>
          </div>
          <div className="tabla-scroll">
            <table className="table tramos">
              <caption className="hint">Los tramos del modelo, sobre todo lo recuperado en el mes</caption>
              <thead><tr><th scope="col">Lo recuperado en el mes</th><th scope="col">Porcentaje</th></tr></thead>
              <tbody>
                {datos.tramos.map((t) => (
                  <tr key={t.desde} className={t.vigente ? "vigente" : ""} aria-current={t.vigente ? "true" : undefined}>
                    <td>{tramo(t)}{t.vigente ? <span className="badge badge-ok">este mes</span> : null}</td>
                    <td className="num">{porcentaje(t.porcentaje)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      ) : null}
    </section>
  );
}
