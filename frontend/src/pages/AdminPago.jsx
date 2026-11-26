import { useEffect, useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import { deudaDelPanel, pagoDelPanel, verificarPago } from "../api/admin";
import Cargando from "../components/Cargando";
import { IconoVolver } from "../components/Iconos";
import { dinero, ESTADO_DE_PAGO, ESTADO_DEUDA, fechaHora, PASARELA, rutLegible } from "../utils/formato";

/** Qué le pasó al pago en cada paso de su historia, en palabras. */
const PASO = {
  created: "Se abrió el cobro",
  authorized: "La pasarela lo autorizó",
  paid: "Quedó pagado",
  failed: "Falló",
  expired: "Venció sin pagarse",
  refunded: "Se devolvió",
  duplicated: "Se cobró dos veces",
};

/** Quién lo hizo: el deudor en el portal, la pasarela (su aviso o la consulta) o un admin en el panel. */
const ORIGEN = { portal: "Portal", webhook: "Pasarela", manual: "Panel" };

/** Lo que dijo la pasarela al verificar, en una frase. */
function resultado(r) {
  if (r.ahora === "created") return `${PASARELA[r.pasarela]} dice que sigue en curso: el pago sigue abierto.`;
  if (r.ahora === "paid") return `${PASARELA[r.pasarela]} confirmó el pago: quedó pagado.`;
  return `${PASARELA[r.pasarela]} dice que no se pagó: quedó ${ESTADO_DE_PAGO[r.ahora]?.texto.toLowerCase() || r.ahora}.`;
}

/**
 * El detalle de un pago en el panel (#435): de qué deuda es, su historia y,
 * si sigue abierto, el boton para preguntarle a su pasarela como quedo. Sin
 * datos del deudor: para eso, su ficha en Deudores, que queda en la bitacora.
 */
export default function AdminPago() {
  const { id } = useParams();
  const desde = useLocation().state?.desde || "";
  const [detalle, setDetalle] = useState(null);
  const [deuda, setDeuda] = useState(null);
  const [noExiste, setNoExiste] = useState(false);
  const [error, setError] = useState("");
  const [verificando, setVerificando] = useState(false);
  const [aviso, setAviso] = useState(null);
  const [vuelta, setVuelta] = useState(0);

  useEffect(() => {
    setNoExiste(false);
    setError("");
    pagoDelPanel(id)
      .then((d) => {
        setDetalle(d);
        deudaDelPanel(d.pago.deuda).then(setDeuda).catch(() => setDeuda("error"));
      })
      .catch((e) => (e.status === 404 ? setNoExiste(true) : setError(e.message)));
  }, [id, vuelta]);

  async function verificar() {
    if (!window.confirm(`¿Preguntarle a ${PASARELA[detalle.pago.pasarela]} cómo quedó el pago ${id}? `
      + "Si ya se cobró, queda pagado; si no se pagó, se cierra. Queda en la bitácora del panel.")) return;
    setVerificando(true);
    setAviso(null);
    try {
      const r = await verificarPago(id);
      setAviso({ ok: true, texto: resultado(r) });
      setVuelta((v) => v + 1);
    } catch (e) {
      setAviso({ ok: false, texto: e.message });
    } finally {
      setVerificando(false);
    }
  }

  const volver = (
    <Link className="btn btn-ghost btn-sm" to={`/admin/pagos${desde}`} style={{ marginBottom: 14 }}>
      <IconoVolver size={16} /> Pagos
    </Link>
  );

  if (noExiste) {
    return (
      <div>
        {volver}
        <div className="empty bloque">No hay ningún pago con el número {id}.</div>
      </div>
    );
  }
  if (error && !detalle) {
    return (
      <div>
        {volver}
        <div className="error" role="alert">{error}</div>
      </div>
    );
  }
  if (!detalle) return <Cargando tarjetas={2} />;

  const { pago, historia } = detalle;
  const estado = ESTADO_DE_PAGO[pago.estado] || { texto: pago.estado, clase: "badge-muted" };

  return (
    <div>
      {volver}
      <header className="topbar aparece">
        <div>
          <h1>Pago {pago.id}</h1>
          <p>
            {PASARELA[pago.pasarela] || pago.pasarela} · {dinero(pago.monto, pago.moneda)} ·{" "}
            {pago.cuotas} {pago.cuotas === 1 ? "cuota" : "cuotas"}
          </p>
        </div>
        <span className={`badge ${estado.clase}`}>{estado.texto}</span>
      </header>

      {aviso ? (
        <div className={aviso.ok ? "notice" : "error"} role={aviso.ok ? "status" : "alert"}>{aviso.texto}</div>
      ) : null}

      {pago.estado === "created" ? (
        <section className="card bloque aparece" aria-labelledby="pago-abierto">
          <h2 id="pago-abierto">Sigue abierto</h2>
          <p className="hint">
            {pago.abiertoHaceMasDeUnaHora
              ? "Lleva más de una hora abierto. Si el deudor dice que pagó, o el pago quedó pegado, pregúntale a la pasarela cómo quedó."
              : "Se abrió hace menos de una hora: lo normal es que el deudor todavía esté pagando."}
          </p>
          <button type="button" className="btn btn-primary btn-sm" onClick={verificar} disabled={verificando}>
            {verificando ? "Preguntando…" : `Verificar con ${PASARELA[pago.pasarela] || "la pasarela"}`}
          </button>
        </section>
      ) : null}

      {pago.duplicado ? (
        <p className="hint" role="note">
          Este pago se cobró dos veces. La devolución la registra la empresa que cobra, en Pagos recibidos.
        </p>
      ) : null}

      <section className="card bloque aparece" aria-labelledby="pago-resumen">
        <h2 id="pago-resumen">Resumen</h2>
        <dl className="ficha-datos">
          <div><dt>Abierto</dt><dd>{fechaHora(pago.creado)}</dd></div>
          <div><dt>Pagado</dt><dd>{pago.pagado ? fechaHora(pago.pagado) : "No"}</dd></div>
          <div>
            <dt>Monto</dt>
            <dd>
              {dinero(pago.monto, pago.moneda)}
              {pago.moneda !== "CLP" && pago.montoEnPesos != null ? ` (${dinero(pago.montoEnPesos)})` : ""}
            </dd>
          </div>
          <div><dt>Acreedor</dt><dd>{deuda && deuda !== "error" ? deuda.acreedor : rutLegible(pago.acreedorRut)}</dd></div>
          {deuda === "error" ? (
            <div><dt>Deuda</dt><dd>{pago.deuda} <span className="hint">(ms-debt no respondió)</span></dd></div>
          ) : deuda ? (
            <>
              <div><dt>Deuda</dt><dd>{deuda.concepto} · {deuda.referencia}</dd></div>
              <div><dt>La cobra</dt><dd>{deuda.cobra}{deuda.campana ? ` · ${deuda.campana}` : ""}</dd></div>
              <div><dt>Estado de la deuda</dt><dd>{ESTADO_DEUDA[deuda.estado] || deuda.estado}</dd></div>
            </>
          ) : (
            <div><dt>Deuda</dt><dd>{pago.deuda}</dd></div>
          )}
        </dl>
        {deuda && deuda !== "error" ? (
          <p className="hint" style={{ marginBottom: 0 }}>
            ¿Quién es el deudor? <Link to={`/admin/deudores?q=${encodeURIComponent(deuda.referencia)}`}>Buscarlo en
            Deudores</Link>: abrir su ficha queda en la bitácora.
          </p>
        ) : null}
      </section>

      <section className="card bloque aparece" aria-labelledby="pago-historia">
        <h2 id="pago-historia">Historia</h2>
        {historia.length === 0 ? (
          <div className="empty">Este pago no tiene pasos anotados.</div>
        ) : (
          <div className="tabla-scroll">
            <table className="table">
              <thead>
                <tr><th scope="col">Qué pasó</th><th scope="col">Cuándo</th></tr>
              </thead>
              <tbody>
                {historia.map((e, i) => (
                  <tr key={i}>
                    <td>
                      <b>{PASO[e.tipo] || e.tipo}</b>
                      <span className="sub">
                        {ORIGEN[e.origen] || e.origen}
                        {e.firmaValida === false ? " · firma no válida" : ""}
                      </span>
                    </td>
                    <td>{fechaHora(e.ocurrioEn)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
