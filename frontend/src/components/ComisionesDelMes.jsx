import { useEffect, useState } from "react";
import { comisionesDelMes } from "../api/admin";
import { dinero, porcentaje, rutLegible } from "../utils/formato";
import { mesDeHoy, mesEnPalabras } from "./ComisionDelMes";

/**
 * El modelo de negocio, visto por el admin (#200): lo que DataBridge le cobra a cada empresa en el mes. La
 * paga quien cargó la deuda (la agencia, o el acreedor que cobra directo), así que ningún pago se cuenta dos
 * veces.
 */
export default function ComisionesDelMes() {
  const [mes, setMes] = useState(mesDeHoy());
  const [datos, setDatos] = useState(null);
  const [error, setError] = useState("");
  const [cargando, setCargando] = useState(true);

  useEffect(() => {
    let vigente = true;
    setError("");
    setDatos(null);
    setCargando(true);
    comisionesDelMes(mes)
      .then((d) => vigente && setDatos(d))
      .catch((err) => vigente && setError(err.message))
      .finally(() => vigente && setCargando(false));
    return () => { vigente = false; };
  }, [mes]);

  return (
    <section className="card aparece comision-del-mes" aria-labelledby="titulo-comisiones">
      <div className="card-cab">
        <h3 id="titulo-comisiones">Lo que DataBridge cobra{datos ? ` en ${mesEnPalabras(datos.mes)}` : ""}</h3>
        <label className="selector-mes">
          Mes{" "}
          <input type="month" value={mes} max={mesDeHoy()} onChange={(e) => e.target.value && setMes(e.target.value)} />
        </label>
      </div>
      <p className="hint" style={{ marginTop: -6 }}>
        A cada empresa, un porcentaje de lo que recuperó en el mes: 6, 10, 12 o 15 % según el tramo, sobre todo lo
        recuperado.
      </p>
      {error ? <div className="error">{error}</div> : null}
      {cargando ? <p className="hint" role="status">Cargando comisiones…</p> : null}
      {datos && datos.empresas.length === 0 ? (
        <div className="empty">Ninguna empresa recuperó pagos en {mesEnPalabras(datos.mes)}.</div>
      ) : null}
      {datos && datos.empresas.length ? (
        <div className="tabla-scroll">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Empresa</th><th scope="col" className="num">Recuperado (G)</th>
                <th scope="col" className="num">Tramo</th><th scope="col" className="num">Comisión (P)</th>
              </tr>
            </thead>
            <tbody>
              {datos.empresas.map((e) => (
                <tr key={e.rut}>
                  <td><b>{e.nombre}</b><div className="hint mono">{rutLegible(e.rut)} · {e.pagos} {e.pagos === 1 ? "pago" : "pagos"}</div></td>
                  <td className="num">{dinero(e.recuperado)}</td>
                  <td className="num">{porcentaje(e.porcentaje)}</td>
                  <td className="num"><b>{dinero(e.comision)}</b></td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr><th scope="row">Total</th><td className="num">{dinero(datos.recuperado)}</td><td /><td className="num"><b>{dinero(datos.comision)}</b></td></tr>
            </tfoot>
          </table>
        </div>
      ) : null}
    </section>
  );
}
