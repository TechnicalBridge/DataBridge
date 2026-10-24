import { useEffect, useState } from "react";
import { descargarComprobante, listarDevueltos, listarPagos } from "../api/pagos";
import { dinero, porMoneda, queSePago } from "../utils/formato";
import Cargando from "../components/Cargando";
import LogoPasarela from "../components/LogoPasarela";
import { IconoDescargar } from "../components/Iconos";

/**
 * Los pagos del deudor que ya se abonaron a sus deudas, con el comprobante de
 * cada uno. Un cobro que quedo a medias en la pasarela no aparece: no paso.
 * Aparte, los pagos duplicados que la empresa ya le devolvio (#153).
 */
export default function Historial() {
  const [pagos, setPagos] = useState(null);
  const [error, setError] = useState("");
  const [bajando, setBajando] = useState(null);
  const [devueltos, setDevueltos] = useState([]);

  useEffect(() => {
    listarPagos()
      .then(setPagos)
      .catch((err) => {
        setError(err.status === 404 ? "" : err.message);
        setPagos([]);
      });
    listarDevueltos().then(setDevueltos).catch(() => setDevueltos([]));
  }, []);

  async function comprobante(id) {
    setBajando(id);
    try {
      await descargarComprobante(id);
    } catch (err) {
      setError(err.message);
    } finally {
      setBajando(null);
    }
  }

  if (pagos === null) return <Cargando tarjetas={1} />;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Historial de pagos</h1>
          <p>Lo que ya pagaste y quedó abonado a tus deudas.</p>
        </div>
      </header>
      {error ? <div className="error">{error}</div> : null}

      {pagos.length ? (
        <section className="grid-3 stats bloque">
          <div className="card stat aparece" style={{ "--i": 1 }}>
            <span>Pagado en total</span>
            <b className="totales">
              {porMoneda(pagos, "monto").map(([moneda, total]) => <span key={moneda}>{dinero(total, moneda)}</span>)}
            </b>
          </div>
          <div className="card stat aparece" style={{ "--i": 2 }}>
            <span>Pagos</span>
            <b>{pagos.length}</b>
          </div>
          <div className="card stat aparece" style={{ "--i": 3 }}>
            <span>Último pago</span>
            <b style={{ fontSize: 22 }}>{new Date(pagos[0].pagadoEn).toLocaleDateString("es-CL", { day: "numeric", month: "long" })}</b>
          </div>
        </section>
      ) : null}

      <div className="card aparece" style={{ "--i": 4 }}>
        {pagos.length === 0 ? (
          <div className="empty">Todavía no tienes pagos. Cuando pagues, cada uno queda aquí con su comprobante.</div>
        ) : (
          <div className="lista">
            {pagos.map((p, i) => {
              const dia = new Date(p.pagadoEn);
              return (
                <div key={p.id} className="fila" style={{ "--i": i }}>
                  <div className="fecha-caja">
                    <b>{dia.getDate()}</b>
                    <span>{dia.toLocaleDateString("es-CL", { month: "short" }).replace(".", "")}</span>
                  </div>
                  <div className="fila-que">
                    <b>{queSePago(p)}</b>
                    <span>{p.concepto} con {p.acreedor}, ref. {p.externalId}</span>
                    <div className="fila-acciones" style={{ justifyContent: "flex-start" }}>
                      <LogoPasarela id={p.pasarela} alto={18} />
                      <button type="button" className="btn btn-ghost btn-sm" disabled={bajando === p.id}
                              onClick={() => comprobante(p.id)}>
                        {bajando === p.id ? <span className="girando" /> : <IconoDescargar size={16} />}
                        Comprobante
                      </button>
                    </div>
                  </div>
                  <div className="fila-monto">
                    <b>{dinero(p.monto, p.moneda)}</b>
                    {p.moneda === "UF" && p.montoClp ? <span>{dinero(p.montoClp)} ese día</span> : null}
                    {Number(p.descuento) > 0 ? <span>Con {dinero(p.descuento, p.moneda)} de descuento</span> : null}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {devueltos.length ? (
        <div className="card aparece" style={{ "--i": 5, marginTop: 18 }}>
          <h3>{devueltos.length === 1 ? "Un pago que se te devolvió" : "Pagos que se te devolvieron"}</h3>
          <div className="lista">
            {devueltos.map((p, i) => {
              const dia = new Date(p.devueltoEn || p.createdAt);
              return (
                <div key={p.id} className="fila" style={{ "--i": i }}>
                  <div className="fecha-caja">
                    <b>{dia.getDate()}</b>
                    <span>{dia.toLocaleDateString("es-CL", { month: "short" }).replace(".", "")}</span>
                  </div>
                  <div className="fila-que">
                    <b>Pago duplicado, devuelto</b>
                    <span>
                      Pagaste dos veces las mismas cuotas y el segundo pago no se abonó: la empresa te lo devolvió
                      {p.referenciaDevolucion ? `, ref. ${p.referenciaDevolucion}` : ""}.
                    </span>
                    <div className="fila-acciones" style={{ justifyContent: "flex-start" }}>
                      <LogoPasarela id={p.gateway} alto={18} />
                    </div>
                  </div>
                  <div className="fila-monto">
                    <b>{p.amountClp ? dinero(p.amountClp) : dinero(p.amount, p.currency)}</b>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      ) : null}
    </div>
  );
}
