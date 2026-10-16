import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { cargarCartera, descargarPlantillaExcel, opcionesDeCarga, revisarCartera } from "../api/deudas";
import { dinero } from "../utils/formato";
import { descargarPlantilla, idDeCarga } from "../utils/plantillaCartera";
import FormCampana from "../components/FormCampana";
import Cargando from "../components/Cargando";

const PASOS = ["La campaña", "La planilla", "La revisión", "Listo"];
const ENTRAN = ["registrada", "actualizada", "sin_cambios"];

/** Las columnas de la plantilla de Excel: las mismas que lee ms-debt (CarteraExcel). */
const COLUMNAS_DEUDORES = ["RUT", "Tipo", "Nombre", "Correo", "Teléfono"];
const COLUMNAS_DEUDAS = ["Id de la deuda", "RUT del deudor", "Acción", "Moneda", "Concepto de la deuda", "Mes", "Monto",
  "Vencimiento"];
const OPCIONALES_DEUDAS = ["Concepto del mes", "Tasa de interés mensual", "Motivo del retiro", "Referencias"];

/**
 * Cargar deudores: es la unica forma en que los datos entran a DataBridge.
 * Siempre en una campaña, y con una revision antes de confirmar: la planilla
 * pasa por la misma ingesta, pero nada queda guardado hasta que se confirma.
 */
export default function Cargar() {
  const [params] = useSearchParams();
  const [opciones, setOpciones] = useState(null);
  const [acreedor, setAcreedor] = useState("");
  const [campana, setCampana] = useState(params.get("campana") || "");
  const [creando, setCreando] = useState(false);
  const [paso, setPaso] = useState(0);
  const [archivo, setArchivo] = useState(null);
  const [lote, setLote] = useState("");
  const [arrastrando, setArrastrando] = useState(false);
  const [revision, setRevision] = useState(null);
  const [resultado, setResultado] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  //  Los problemas de una planilla de Excel, cada uno con su hoja, fila y columna.
  const [detalles, setDetalles] = useState([]);

  /** Las campañas de cada acreedor, y cuál queda elegida. */
  async function leerOpciones(elegir) {
    const o = await opcionesDeCarga();
    setOpciones(o);
    const buscada = elegir || campana;
    const suyo = o.acreedores?.find((a) => a.campanas?.some((c) => c.idExterno === buscada));
    setAcreedor((actual) => suyo?.rut || actual || o.acreedores?.[0]?.rut || "");
    if (elegir) setCampana(elegir);
    return { o, suyo };
  }

  useEffect(() => {
    //  Si se llegó desde una campaña (Cargar deudores), se parte en la planilla.
    leerOpciones()
      .then(({ suyo }) => {
        const pedida = suyo?.campanas?.find((c) => c.idExterno === campana);
        if (pedida && pedida.estado !== "terminada") setPaso(1);
        //  Una que no existe, o que ya terminó, no queda elegida.
        else setCampana("");
      })
      .catch((err) => setError(err.message));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const elegido = opciones?.acreedores?.find((a) => a.rut === acreedor);
  const disponibles = (elegido?.campanas || []).filter((c) => c.estado !== "terminada");
  const laCampana = disponibles.find((c) => c.idExterno === campana);

  function fallo(err) {
    setError(err.message);
    setDetalles(err.detalles || []);
  }

  function elegirArchivo(nuevo) {
    setArchivo(nuevo);
    setLote(nuevo ? idDeCarga() : "");
    setRevision(null);
    setError("");
    setDetalles([]);
  }

  async function plantilla() {
    try {
      await descargarPlantillaExcel();
    } catch (err) {
      fallo(err);
    }
  }

  function formulario() {
    const form = new FormData();
    form.append("archivo", archivo);
    form.append("lote_id_externo", lote);
    form.append("acreedor_rut", acreedor);
    form.append("campana_id_externo", campana);
    return form;
  }

  async function revisar() {
    setBusy(true);
    setError("");
    setDetalles([]);
    try {
      setRevision(await revisarCartera(formulario()));
      setPaso(2);
    } catch (err) {
      fallo(err);
    } finally {
      setBusy(false);
    }
  }

  async function confirmar() {
    setBusy(true);
    setError("");
    setDetalles([]);
    try {
      setResultado(await cargarCartera(formulario()));
      setPaso(3);
    } catch (err) {
      fallo(err);
    } finally {
      setBusy(false);
    }
  }

  function otraPlanilla() {
    elegirArchivo(null);
    setResultado(null);
    setPaso(1);
  }

  async function campanaCreada(nueva) {
    await leerOpciones(nueva.idExterno);
    setCreando(false);
  }

  if (opciones === null && !error) return <Cargando tarjetas={2} />;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Cargar cartera</h1>
          <p>
            Tus deudores entran a DataBridge dentro de una campaña. Antes de cargarlos ves qué va a pasar con
            cada uno, y nada queda guardado hasta que confirmas.
          </p>
        </div>
      </header>

      <ol className="asistente aparece" aria-label="Pasos">
        {PASOS.map((nombre, i) => (
          <li key={nombre} className={i < paso ? "hecho" : i === paso ? "actual" : ""}
              aria-current={i === paso ? "step" : undefined}>
            {nombre}
          </li>
        ))}
      </ol>

      {error ? (
        <div className="error" role="alert">
          {error}
          {detalles.length ? (
            <ul className="lista-revision problemas-planilla">
              {detalles.map((d) => <li key={d}>{d}</li>)}
            </ul>
          ) : null}
        </div>
      ) : null}

      <div className="grid-2">
        <div className="card aparece" style={{ "--i": 1 }}>
          {paso === 0 ? (
            <PasoCampana opciones={opciones} acreedor={acreedor} setAcreedor={(rut) => { setAcreedor(rut); setCampana(""); }}
                         disponibles={disponibles} campana={campana} setCampana={setCampana} elegido={elegido}
                         creando={creando} setCreando={setCreando} onCreada={campanaCreada}
                         onSeguir={() => setPaso(1)} />
          ) : null}

          {paso === 1 ? (
            <PasoPlanilla campana={laCampana} archivo={archivo} elegirArchivo={elegirArchivo}
                          arrastrando={arrastrando} setArrastrando={setArrastrando} busy={busy}
                          onRevisar={revisar} onVolver={() => setPaso(0)} onPlantilla={plantilla} />
          ) : null}

          {paso === 2 && revision ? (
            <PasoRevision revision={revision} campana={laCampana} busy={busy}
                          onConfirmar={confirmar} onOtroArchivo={otraPlanilla} />
          ) : null}

          {paso === 3 && resultado ? (
            <PasoListo resultado={resultado} campana={laCampana} onOtra={otraPlanilla} />
          ) : null}
        </div>

        <div className="aparece" style={{ "--i": 2 }}>
          <Formato minDiasMora={opciones?.minDiasMora ?? 30} />
        </div>
      </div>
    </div>
  );
}

function PasoCampana({
  opciones, acreedor, setAcreedor, disponibles, campana, setCampana, elegido, creando, setCreando, onCreada, onSeguir,
}) {
  const acreedores = opciones?.acreedores || [];
  const pausada = disponibles.find((c) => c.idExterno === campana)?.estado === "pausada";
  if (acreedores.length === 0) {
    return <div className="empty">No hay por cuenta de quién cargar: tu empresa no es acreedora ni tiene un mandato vigente.</div>;
  }
  return (
    <>
      <h3>¿En qué campaña entran?</h3>
      {acreedores.length > 1 ? (
        <div className="field">
          <label htmlFor="acreedor">Acreedor</label>
          <select id="acreedor" value={acreedor} onChange={(e) => setAcreedor(e.target.value)}>
            {acreedores.map((a) => <option key={a.rut} value={a.rut}>{a.nombre}</option>)}
          </select>
        </div>
      ) : null}

      {creando ? (
        <>
          <p className="hint" style={{ marginTop: 0 }}>
            La campaña dice cuándo y cuántas veces se contacta a cada deudor. Después la puedes cambiar en
            {" "}<Link className="link-btn" to="/databridge/campanas">Campañas</Link>.
          </p>
          <FormCampana acreedores={elegido ? [elegido] : []} prefijo="nueva" onGuardada={onCreada}
                       onCancelar={() => setCreando(false)} />
        </>
      ) : (
        <>
          {disponibles.length === 0 ? (
            <p className="hint" style={{ marginTop: 0 }}>Todavía no hay campañas en curso para {elegido?.nombre}. Crea la primera.</p>
          ) : (
            <fieldset className="opciones-campana">
              <legend className="sr-only">Campañas</legend>
              {disponibles.map((c) => (
                <label key={c.idExterno} className={`opcion${campana === c.idExterno ? " on" : ""}`}>
                  <input type="radio" name="campana" value={c.idExterno} checked={campana === c.idExterno}
                         onChange={() => setCampana(c.idExterno)} />
                  <span>
                    <b>{c.nombre}</b>
                    <span className="mono">{c.idExterno}</span>
                  </span>
                  {c.estado === "pausada" ? <span className="badge badge-warn">Pausada</span> : null}
                </label>
              ))}
            </fieldset>
          )}
          {pausada ? (
            <p className="hint">
              Está pausada: a los deudores les llega su invitación, pero los contactos de la campaña esperan
              a que la reanudes.
            </p>
          ) : null}
          <div className="acciones-fila" style={{ marginTop: 14 }}>
            <button type="button" className="btn btn-primary btn-sm" disabled={!campana} onClick={onSeguir}>
              Seguir
            </button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setCreando(true)}>
              Crear una campaña nueva
            </button>
          </div>
        </>
      )}
    </>
  );
}

function PasoPlanilla({ campana, archivo, elegirArchivo, arrastrando, setArrastrando, busy, onRevisar, onVolver,
  onPlantilla }) {
  return (
    <div
      className={`dropzone zona-planilla ${arrastrando ? "on" : ""}`}
      onDragOver={(e) => { e.preventDefault(); setArrastrando(true); }}
      onDragLeave={() => setArrastrando(false)}
      onDrop={(e) => {
        e.preventDefault();
        setArrastrando(false);
        if (e.dataTransfer.files?.[0]) elegirArchivo(e.dataTransfer.files[0]);
      }}
    >
      <div className="card-cab">
        <h3>La planilla</h3>
        <button type="button" className="link-btn" onClick={onPlantilla}>Descargar la plantilla de Excel</button>
      </div>
      <p className="hint" style={{ marginTop: -6 }}>
        Entran en <b>{campana?.nombre}</b>.{" "}
        <button type="button" className="link-btn" onClick={onVolver}>Cambiar de campaña</button>
      </p>
      <p className="hint" style={{ margin: "4px 0 12px" }}>
        {archivo
          ? <>Archivo: <b>{archivo.name}</b> ({Math.ceil(archivo.size / 1024)} KB)</>
          : "Arrastra aquí la planilla de Excel (.xlsx), o elígela. También sirve el CSV del contrato."}
      </p>
      <div className="acciones-fila">
        <label className="btn btn-ghost btn-sm">
          Elegir archivo
          <input type="file" hidden onChange={(e) => elegirArchivo(e.target.files?.[0] || null)}
                 accept=".xlsx,.csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,text/csv" />
        </label>
        <button type="button" className="btn btn-primary btn-sm" disabled={!archivo || busy} onClick={onRevisar}>
          {busy ? <><span className="girando" /> Revisando…</> : "Revisar la planilla"}
        </button>
      </div>
      <p className="hint">Revisar no guarda nada ni le escribe a nadie.</p>
    </div>
  );
}

/** Cuánto suma lo que entra, por moneda: "$1.560.000 y UF 38,5". */
function montos(entrarian) {
  return (entrarian || []).map((m) => dinero(m.monto, m.moneda)).join(" y ");
}

function PasoRevision({ revision, campana, busy, onConfirmar, onOtroArchivo }) {
  const resultados = revision.cartera?.resultados || [];
  const entran = resultados.filter((r) => ENTRAN.includes(r.resultado));
  const rechazadas = resultados.filter((r) => r.resultado === "rechazada");
  const retiradas = resultados.filter((r) => r.resultado === "retirada");
  const alDia = resultados.filter((r) => r.resultado === "al_dia");
  const conAvisos = entran.filter((r) => r.avisos?.length);
  const hayQueCargar = entran.length + retiradas.length > 0;
  return (
    <>
      <h3>Esto pasaría al cargarla</h3>
      {revision.cartera?.repetido ? (
        <div className="notice">Esa planilla ya se cargó: confirmarla otra vez no cambia nada.</div>
      ) : null}
      <div className="cifras-revision">
        <div className="cifra entran">
          <b>{entran.length}</b>
          <span>{entran.length === 1 ? "deuda entraría" : "deudas entrarían"}{entran.length ? `: ${montos(revision.entrarian)}` : ""}</span>
        </div>
        <div className={`cifra${rechazadas.length ? " rechazo" : ""}`}>
          <b>{rechazadas.length}</b>
          <span>{rechazadas.length === 1 ? "se rechazaría" : "se rechazarían"}</span>
        </div>
        {retiradas.length ? (
          <div className="cifra"><b>{retiradas.length}</b><span>{retiradas.length === 1 ? "saldría" : "saldrían"} de la cobranza</span></div>
        ) : null}
        {alDia.length ? (
          <div className="cifra"><b>{alDia.length}</b><span>al día: no se guardan</span></div>
        ) : null}
      </div>

      {rechazadas.length ? (
        <>
          <h4 className="lista-grupo">Las que no entrarían, y por qué</h4>
          <ul className="lista-revision">
            {rechazadas.map((r) => (
              <li key={r.id_externo}>
                <b className="mono">{r.id_externo}</b>: {(r.errores || []).map((e) => e.mensaje).join(" · ") || "rechazada"}
              </li>
            ))}
          </ul>
        </>
      ) : null}
      {conAvisos.length ? (
        <>
          <h4 className="lista-grupo">Las que entrarían con un aviso</h4>
          <ul className="lista-revision">
            {conAvisos.map((r) => (
              <li key={r.id_externo}><b className="mono">{r.id_externo}</b>: {r.avisos.map((a) => a.mensaje).join(" · ")}</li>
            ))}
          </ul>
        </>
      ) : null}

      <div className="acciones-fila" style={{ marginTop: 6 }}>
        <button type="button" className="btn btn-primary btn-sm" disabled={!hayQueCargar || busy} onClick={onConfirmar}>
          {busy ? <><span className="girando" /> Cargando…</>
            : entran.length ? `Cargar ${entran.length} ${entran.length === 1 ? "deuda" : "deudas"} en ${campana?.nombre || "la campaña"}`
              : "Confirmar"}
        </button>
        <button type="button" className="btn btn-ghost btn-sm" disabled={busy} onClick={onOtroArchivo}>
          Elegir otro archivo
        </button>
      </div>
      {!hayQueCargar ? <p className="hint">No hay nada que cargar: corrige la planilla y vuelve a revisarla.</p> : null}
    </>
  );
}

function PasoListo({ resultado, campana, onOtra }) {
  const aceptadas = resultado.aceptadas ?? 0;
  return (
    <>
      <h3>Listo</h3>
      <div className="notice">
        {resultado.repetido ? "Esa planilla ya estaba cargada: no se duplicó nada. " : ""}
        <b>{aceptadas} {aceptadas === 1 ? "deuda quedó" : "deudas quedaron"}</b> en {campana?.nombre || "la campaña"}
        {resultado.rechazadas ? `, y ${resultado.rechazadas} no entraron` : ""}.
      </div>
      <p className="hint">
        Cada deudor nuevo recibe su invitación con su código de acceso, dentro del horario que permite la ley.
      </p>
      <div className="acciones-fila" style={{ marginTop: 12 }}>
        <Link className="btn btn-primary btn-sm" to="/databridge">Ver la cartera</Link>
        <Link className="btn btn-ghost btn-sm" to="/databridge/campanas">Ver las campañas</Link>
        <button type="button" className="btn btn-ghost btn-sm" onClick={onOtra}>Cargar otra planilla</button>
      </div>
    </>
  );
}

function Formato({ minDiasMora }) {
  return (
    <div className="card">
      <h3>La planilla</h3>
      <p style={{ margin: 0 }}>
        Un Excel con dos hojas. En <b>Deudores</b>, una fila por persona. En <b>Deudas</b>, una fila por cada
        <b> mes</b> que se debe, con el RUT de su deudor: quien debe tres meses de arriendo lleva tres filas, con el
        mismo id de la deuda. La plantilla trae las listas desplegables, las fechas y los montos con su tipo, y una
        hoja de <i>Instrucciones</i> con cada columna y un ejemplo.
      </p>
      <h4 className="lista-grupo">Deudores</h4>
      <div className="columnas-csv">
        {COLUMNAS_DEUDORES.map((c) => <code key={c}>{c}</code>)}
      </div>
      <h4 className="lista-grupo">Deudas</h4>
      <div className="columnas-csv">
        {COLUMNAS_DEUDAS.map((c) => <code key={c}>{c}</code>)}
        {OPCIONALES_DEUDAS.map((c) => <code key={c} className="opcional">{c}</code>)}
      </div>
      <p className="hint">
        Solo deudores morosos: una deuda entra cuando su mes impago más antiguo lleva al menos {minDiasMora} días
        vencido, y las demás se rechazan con su motivo. Un cliente al día va con la acción <code>al día</code>, sin
        mes, y para sacar una deuda de la cobranza (pagó en tu oficina), con <code>retirar</code> y el motivo.
      </p>
      <p className="hint">
        ¿Tu sistema exporta CSV? También sirve, con las columnas del contrato:{" "}
        <button type="button" className="link-btn" onClick={descargarPlantilla}>plantilla en CSV</button>.
      </p>
    </div>
  );
}
