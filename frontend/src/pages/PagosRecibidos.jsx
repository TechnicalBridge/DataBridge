import { useEffect, useState } from "react";
import { descargarComprobante, listarPagos, listarParaDevolver, registrarDevolucion } from "../api/pagos";
import { dinero, fechaHora, porMoneda, queSePago, rutLegible } from "../utils/formato";
import { descargarCsv, montoParaExcel } from "../utils/exportar";
import Cargando from "../components/Cargando";
import LogoPasarela, { PASARELAS, nombreDePasarela } from "../components/LogoPasarela";
import { IconoAlerta, IconoDescargar } from "../components/Iconos";

/** Los pagos que entraron a la cartera de la empresa, con filtro y planilla. */
export default function PagosRecibidos() {
  const [pagos, setPagos] = useState(null);
  const [paraDevolver, setParaDevolver] = useState([]);
  const [pasarela, setPasarela] = useState("todas");
  const [buscar, setBuscar] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    listarPagos()
      .then(setPagos)
      .catch((err) => {
        setError(err.message);
        setPagos([]);
      });
    listarParaDevolver().then(setParaDevolver).catch(() => setParaDevolver([]));
  }, []);

  if (pagos === null) return <Cargando tarjetas={1} />;

  const texto = buscar.trim().toLowerCase().replace(/\./g, "");
  const visibles = pagos.filter((p) =>
    (pasarela === "todas" || p.pasarela === pasarela)
    && (!texto || [p.deudor, p.deudorRut, p.externalId].some((v) => String(v).toLowerCase().includes(texto))));

  async function marcarDevuelto(id) {
    const ref = window.prompt("Referencia o comprobante de devolución en la pasarela (opcional):", "");
    if (ref === null) return;
    try {
      await registrarDevolucion(id, ref);
      setParaDevolver((prev) => prev.filter((p) => p.id !== id));
    } catch (err) {
      alert("No se pudo registrar la devolución: " + err.message);
    }
  }

  function exportar() {
    descargarCsv("pagos-recibidos.csv",
      ["Fecha", "Deudor", "RUT", "Referencia", "Acreedor", "Que pago", "Medio", "Operacion", "Moneda", "Monto", "Pesos", "Descuento"],
      visibles.map((p) => [new Date(p.pagadoEn).toLocaleString("es-CL"), p.deudor, rutLegible(p.deudorRut),
        p.externalId, p.acreedor, queSePago(p), nombreDePasarela(p.pasarela), p.referencia, p.moneda,
        montoParaExcel(p.monto, p.moneda), p.montoClp ?? "",
        p.descuento ? montoParaExcel(p.descuento, p.moneda) : ""]));
  }

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Pagos recibidos</h1>
          <p>Lo que entró a tu cartera, del pago más nuevo al más antiguo.</p>
        </div>
        <div className="topbar-acciones">
          <button type="button" className="btn btn-ghost btn-sm" disabled={!visibles.length} onClick={exportar}>
            <IconoDescargar size={16} />
            Exportar a Excel
          </button>
        </div>
      </header>
      {error ? <div className="error">{error}</div> : null}

      {paraDevolver.length ? (
        <div className="card bloque aparece">
          <div className="aviso">
            <IconoAlerta />
            <div>
              <b>{paraDevolver.length === 1 ? "Un pago para devolver" : `${paraDevolver.length} pagos para devolver`}</b>
              <span className="sub">
                El deudor pagó dos veces las mismas cuotas y el segundo pago no se abonó. Devuélvelo en la pasarela
                con su referencia.
              </span>
            </div>
          </div>
          <div className="tabla-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Abierto</th>
                  <th>Deudor</th>
                  <th>Medio</th>
                  <th>Referencia en la pasarela</th>
                  <th className="num">Monto</th>
                  <th>Acción</th>
                </tr>
              </thead>
              <tbody>
                {paraDevolver.map((p) => (
                  <tr key={p.id}>
                    <td>{fechaHora(p.createdAt)}</td>
                    <td>{rutLegible(p.deudorRut)}</td>
                    <td><LogoPasarela id={p.gateway} alto={16} /></td>
                    <td>{p.referenciaPasarela}</td>
                    <td className="num">{p.amountClp ? dinero(p.amountClp) : dinero(p.amount, p.currency)}</td>
                    <td>
                      <button type="button" className="btn btn-sm btn-outline" onClick={() => marcarDevuelto(p.id)}>
                        Ya lo devolví
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : null}

      <section className="grid-3 stats bloque">
        <div className="card stat aparece" style={{ "--i": 1 }}>
          <span>Recibido</span>
          <b className="totales">
            {porMoneda(visibles, "monto").map(([moneda, total]) => <span key={moneda}>{dinero(total, moneda)}</span>)}
            {visibles.length === 0 ? dinero(0) : null}
          </b>
        </div>
        <div className="card stat aparece" style={{ "--i": 2 }}>
          <span>Pagos</span>
          <b>{visibles.length}</b>
        </div>
        <div className="card stat aparece" style={{ "--i": 3 }}>
          <span>Deudores que pagaron</span>
          <b>{new Set(visibles.map((p) => p.deudorRut)).size}</b>
        </div>
      </section>

      <div className="card aparece" style={{ "--i": 4 }}>
        <div className="card-cab">
          <div className="filters">
            {["todas", ...Object.keys(PASARELAS)].map((id) => (
              <button key={id} type="button" className={`chip${pasarela === id ? " on" : ""}`} onClick={() => setPasarela(id)}>
                {id === "todas" ? "Todas" : nombreDePasarela(id)}
              </button>
            ))}
          </div>
          <input className="buscador" value={buscar} onChange={(e) => setBuscar(e.target.value)}
                 placeholder="Buscar por deudor, RUT o referencia" aria-label="Buscar" />
        </div>
        {visibles.length === 0 ? (
          <div className="empty">{pagos.length ? "Ningún pago calza con el filtro." : "Todavía no entra ningún pago."}</div>
        ) : (
          <div className="tabla-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Fecha</th>
                  <th>Deudor</th>
                  <th>Qué pagó</th>
                  <th>Medio</th>
                  <th className="num">Monto</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {visibles.map((p) => (
                  <tr key={p.id}>
                    <td>{fechaHora(p.pagadoEn)}</td>
                    <td>{p.deudor}<span className="sub">{rutLegible(p.deudorRut)}</span></td>
                    <td>{queSePago(p)}<span className="sub">Ref. {p.externalId}</span></td>
                    <td><LogoPasarela id={p.pasarela} alto={16} /></td>
                    <td className="num">
                      {dinero(p.monto, p.moneda)}
                      {p.moneda === "UF" && p.montoClp ? <span className="sub">{dinero(p.montoClp)}</span> : null}
                      {Number(p.descuento) > 0 ? (
                        <span className="sub">{dinero(p.descuento, p.moneda)} de intereses condonados</span>
                      ) : null}
                    </td>
                    <td>
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() =>
                        descargarComprobante(p.id).catch((err) => setError(err.message))} aria-label="Comprobante">
                        <IconoDescargar size={16} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
