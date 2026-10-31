import { useEffect, useState } from "react";
import { declararMandato, listarMandatos, renovarMandato, terminarMandato } from "../api/mandatos";
import { ESTADO_DEL_MANDATO, fecha, hoyEnChile, porcentaje, rutLegible } from "../utils/formato";

const VACIO = { rut: "", razonSocial: "", nombre: "", desde: "", hasta: "", mora: "120", descuento: "", declaro: false };

/** Un % del formulario: vacío es 0; si no, de 0 a 100 con hasta dos decimales. */
const porcentajeValido = (v) => v === "" || (/^\d{1,3}([.,]\d{1,2})?$/.test(v) && Number(v.replace(",", ".")) <= 100);

/**
 * Los mandatos de la agencia (#234): por cuenta de qué acreedores cobra. Con la
 * API apagada, nacen aquí. Con uno vigente se crean campañas y se carga la
 * cartera de ese acreedor; terminado o vencido, lo ya cargado se sigue cobrando.
 *
 * Una empresa que cobra lo suyo, sin agencia, no ve nada.
 */
export default function Mandatos({ onCambio }) {
  const [datos, setDatos] = useState(null);
  const [declarando, setDeclarando] = useState(false);
  const [form, setForm] = useState(VACIO);
  const [renovando, setRenovando] = useState(null);
  const [hasta, setHasta] = useState("");
  const [ocupado, setOcupado] = useState(false);
  const [error, setError] = useState("");
  const [hecho, setHecho] = useState("");

  const cargar = () => listarMandatos().then(setDatos);

  useEffect(() => {
    cargar().catch((err) => {
      setError(err.message);
      setDatos({ agencia: false, mandatos: [] });
    });
  }, []);

  if (datos === null) return null;
  if (!datos.agencia) return error ? <div className="error">{error}</div> : null;

  const hoy = hoyEnChile();
  const campo = (nombre) => (e) => setForm((f) => ({ ...f, [nombre]: e.target.value }));
  const mora = Number(form.mora);
  const moraValida = Number.isInteger(mora) && mora >= 30 && mora <= 3650;
  const fechasValidas = !form.hasta || (form.hasta >= (form.desde || hoy) && form.hasta >= hoy);
  const listo = form.rut.trim() && moraValida && fechasValidas && porcentajeValido(form.descuento) && form.declaro;

  async function hacer(accion, mensaje) {
    setOcupado(true);
    setError("");
    setHecho("");
    try {
      const mandato = await accion();
      setHecho(mensaje(mandato));
      await cargar();
      await onCambio?.();
      return true;
    } catch (err) {
      setError(err.message);
      return false;
    } finally {
      setOcupado(false);
    }
  }

  async function declarar(e) {
    e.preventDefault();
    const ok = await hacer(() => declararMandato({
      acreedorRut: form.rut.trim(),
      razonSocial: form.razonSocial.trim() || null,
      nombre: form.nombre.trim() || null,
      desde: form.desde || null,
      hasta: form.hasta || null,
      moraMaximaDias: mora,
      descuentoMaximoMora: form.descuento === "" ? null : Number(form.descuento.replace(",", ".")),
    }), (m) => `Quedó el mandato con ${m.acreedor}. ${m.estado === "vigente"
      ? "Ya puedes crearle campañas y cargar su cartera."
      : `Empieza el ${fecha(m.desde)}.`}`);
    if (ok) {
      setForm(VACIO);
      setDeclarando(false);
    }
  }

  async function renovar(m) {
    const ok = await hacer(() => renovarMandato(m.id, hasta || null),
      (r) => (r.hasta ? `El mandato con ${r.acreedor} sigue hasta el ${fecha(r.hasta)}.`
        : `El mandato con ${r.acreedor} sigue sin fecha de término.`));
    if (ok) setRenovando(null);
  }

  function terminar(m) {
    const deudas = m.deudasEnGestion === 1 ? " La deuda que ya cargaste se sigue cobrando hasta pagarse."
      : m.deudasEnGestion > 1 ? ` Las ${m.deudasEnGestion} deudas que ya cargaste se siguen cobrando hasta pagarse.` : "";
    if (!window.confirm(`¿Terminar el mandato con ${m.acreedor}? Desde ahora no puedes cargar su cartera ni crearle `
      + `campañas.${deudas}`)) {
      return;
    }
    hacer(() => terminarMandato(m.id), (r) => `Terminó el mandato con ${r.acreedor}.`);
  }

  return (
    <div className="card bloque aparece" id="mandatos" style={{ "--i": 1, marginBottom: 16 }}>
      <div className="campana-cabeza">
        <div>
          <h3>Mandatos</h3>
          <p className="hint" style={{ marginTop: 0 }}>
            Por cuenta de qué acreedores cobras. Con un mandato vigente le creas campañas y cargas su cartera.
          </p>
        </div>
        {declarando ? null : (
          <button type="button" className="btn btn-primary btn-sm" onClick={() => { setDeclarando(true); setHecho(""); }}>
            Declarar un mandato
          </button>
        )}
      </div>
      {error ? <div className="error">{error}</div> : null}
      {hecho ? <div className="notice" role="status">{hecho}</div> : null}

      {declarando ? (
        <form className="campana" onSubmit={declarar} style={{ marginBottom: 12 }}>
          <div className="campos-2">
            <div className="field">
              <label htmlFor="m-rut">RUT del acreedor</label>
              <input id="m-rut" value={form.rut} maxLength={20} onChange={campo("rut")} placeholder="76.418.902-7" required />
            </div>
            <div className="field">
              <label htmlFor="m-razon">Razón social</label>
              <input id="m-razon" value={form.razonSocial} maxLength={160} onChange={campo("razonSocial")}
                     placeholder="Patrimonio Inmuebles SpA" aria-describedby="m-razon-ayuda" />
            </div>
          </div>
          <div className="field">
            <label htmlFor="m-nombre">Nombre que ve el deudor (opcional)</label>
            <input id="m-nombre" value={form.nombre} maxLength={120} onChange={campo("nombre")}
                   placeholder="Patrimonio Inmuebles" />
          </div>
          <p className="hint" id="m-razon-ayuda" style={{ marginTop: -6, marginBottom: 14 }}>
            Si DataBridge ya conoce al acreedor, se usan los nombres que tiene. Si no, se registra con estos.
          </p>
          <div className="campos-2">
            <div className="field">
              <label htmlFor="m-desde">Desde</label>
              <input id="m-desde" type="date" value={form.desde} onChange={campo("desde")} />
            </div>
            <div className="field">
              <label htmlFor="m-hasta">Hasta (opcional)</label>
              <input id="m-hasta" type="date" value={form.hasta} min={form.desde > hoy ? form.desde : hoy}
                     onChange={campo("hasta")} />
            </div>
          </div>
          {!fechasValidas ? <div className="error">El término tiene que ser de hoy en adelante y no antes del inicio.</div> : null}
          <div className="campos-2">
            <div className="field">
              <label htmlFor="m-mora">Mora máxima (días)</label>
              <input id="m-mora" type="number" min={30} max={3650} step={1} value={form.mora} onChange={campo("mora")}
                     aria-describedby="m-mora-ayuda" required />
            </div>
            <div className="field">
              <label htmlFor="m-descuento">Descuento máximo de mora (%)</label>
              <input id="m-descuento" inputMode="decimal" placeholder="0" value={form.descuento}
                     onChange={campo("descuento")} aria-describedby="m-mora-ayuda" />
            </div>
          </div>
          <p className="hint" id="m-mora-ayuda" style={{ marginTop: -6, marginBottom: 14 }}>
            Pasada la mora máxima, el caso vuelve al acreedor. El descuento es lo más que el acreedor te autoriza a
            condonar de los intereses de mora; tus campañas no pueden ofrecer más.
          </p>
          {!moraValida ? <div className="error">La mora máxima va de 30 a 3650 días.</div> : null}
          {!porcentajeValido(form.descuento) ? <div className="error">El descuento es un % de 0 a 100.</div> : null}
          <label className="interruptor" style={{ marginBottom: 14 }}>
            <input type="checkbox" className="oculto" checked={form.declaro}
                   onChange={(e) => setForm((f) => ({ ...f, declaro: e.target.checked }))} />
            <span className="riel" aria-hidden="true" />
            <span>
              <b>Declaro que este acreedor le encargó a mi empresa cobrar por su cuenta, en estas condiciones</b>
              <span className="sub">Tu empresa responde por el mandato. DataBridge guarda quién lo declaró y cuándo.</span>
            </span>
          </label>
          <div className="fila-acciones">
            <button type="button" className="btn btn-ghost btn-sm" disabled={ocupado}
                    onClick={() => { setDeclarando(false); setForm(VACIO); }}>
              Cancelar
            </button>
            <button className="btn btn-primary btn-sm" disabled={ocupado || !listo}>
              {ocupado ? <><span className="girando" /> Guardando…</> : "Declarar mandato"}
            </button>
          </div>
        </form>
      ) : null}

      {datos.mandatos.length === 0 ? (
        <div className="empty">
          Todavía no tienes mandatos. Declara el del primer acreedor que te encargó su cobranza para crearle campañas.
        </div>
      ) : (
        <div className="campanas">
          {datos.mandatos.map((m) => {
            const [estado, clase] = ESTADO_DEL_MANDATO[m.estado] || [m.estado, "badge-muted"];
            const activo = m.estado !== "terminado";
            return (
              <div key={m.id} className="campana">
                <div className="campana-cabeza">
                  <div className="fila-que">
                    <b>{m.acreedor}</b>
                    <span>RUT {rutLegible(m.acreedorRut)}</span>
                  </div>
                  <span className={`badge ${clase}`}>{estado}</span>
                </div>
                <div className="totales campana-cifras">
                  <span>Desde el {fecha(m.desde)}</span>
                  <span>{m.hasta ? `Hasta el ${fecha(m.hasta)}` : "Sin fecha de término"}</span>
                  <span>Mora máxima {m.moraMaximaDias} días</span>
                  <span>Descuento máximo {porcentaje(m.descuentoMaximoMora || 0)}</span>
                  <span><b className="num">{m.deudasEnGestion}</b> {m.deudasEnGestion === 1 ? "deuda en gestión" : "deudas en gestión"}</span>
                </div>
                <p className="hint" style={{ margin: "6px 0 0" }}>
                  Lo declaró {m.declaradoPor || "la agencia"} el {fecha(m.declaradoEl)}.
                  {m.renovadoEl ? ` Lo renovó ${m.renovadoPor} el ${fecha(m.renovadoEl)}.` : ""}
                  {m.terminadoEl ? ` Lo terminó ${m.terminadoPor} el ${fecha(m.terminadoEl)}.` : ""}
                  {m.estado === "terminado" || m.estado === "vencido"
                    ? " Lo que se cargó con él se sigue cobrando hasta pagarse." : ""}
                </p>
                {renovando === m.id ? (
                  <div className="campos-2" style={{ marginTop: 10, alignItems: "end" }}>
                    <div className="field" style={{ marginBottom: 0 }}>
                      <label htmlFor={`m-renovar-${m.id}`}>Nueva fecha de término (vacía: sin término)</label>
                      <input id={`m-renovar-${m.id}`} type="date" value={hasta} min={m.desde > hoy ? m.desde : hoy}
                             onChange={(e) => setHasta(e.target.value)} />
                    </div>
                    <div className="fila-acciones" style={{ marginTop: 0 }}>
                      <button type="button" className="btn btn-ghost btn-sm" disabled={ocupado}
                              onClick={() => setRenovando(null)}>Cancelar</button>
                      <button type="button" className="btn btn-primary btn-sm" disabled={ocupado}
                              onClick={() => renovar(m)}>Guardar</button>
                    </div>
                  </div>
                ) : activo ? (
                  <div className="fila-acciones">
                    <button type="button" className="btn btn-ghost btn-sm" disabled={ocupado}
                            onClick={() => { setRenovando(m.id); setHasta(m.hasta || ""); setHecho(""); }}>
                      Renovar
                    </button>
                    <button type="button" className="btn btn-peligro btn-sm" disabled={ocupado} onClick={() => terminar(m)}>
                      Terminar
                    </button>
                  </div>
                ) : null}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
