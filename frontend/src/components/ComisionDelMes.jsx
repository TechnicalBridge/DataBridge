import { useEffect, useState } from "react";
import { comisionDelMes } from "../api/analitica";
import { dinero, porcentaje, mesDeHoy, mesEnPalabras } from "../utils/formato";

/** "Más de $1.000.000 y hasta $5.000.000" */
function tramo(t) {
  if (t.hasta == null) return `Más de ${dinero(t.desde)}`;
  return t.desde === 0 ? `Hasta ${dinero(t.hasta)}` : `Más de ${dinero(t.desde)} y hasta ${dinero(t.hasta)}`;
}

/**
 * El modelo de negocio, visto por la empresa (#200): lo que recupero en el mes, su porcentaje, lo que le paga
 * a DataBridge y lo que le queda, con la cuenta escrita en vez de la formula (#305). onDatos recibe la respuesta, para que Campañas
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
        <h2 id="titulo-comision">Lo que pagas a DataBridge</h2>
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
            <div className="cifra"><b>{dinero(datos.recuperado)}</b><span>Recuperado en {mesEnPalabras(datos.mes)}</span></div>
            <div className="cifra"><b>{datos.recuperado ? porcentaje(datos.porcentaje) : "—"}</b><span>Tu porcentaje</span></div>
            <div className="cifra"><b>{dinero(datos.comision)}</b><span>Lo que pagas a DataBridge</span></div>
            <div className="cifra entran"><b>{dinero(datos.paraLaEmpresa)}</b><span>Lo que queda para tu empresa</span></div>
          </div>
          {/* La cuenta con los montos del mes, en vez de la formula (#305). */}
          {datos.recuperado ? (
            <p className="hint cuenta-comision">
              {porcentaje(datos.porcentaje)} de {dinero(datos.recuperado)} = {dinero(datos.comision)}
            </p>
          ) : null}
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
