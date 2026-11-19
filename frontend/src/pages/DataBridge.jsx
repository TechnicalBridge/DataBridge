import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { comisionDelMes, resumenDeCartera } from "../api/analitica";
import { enviarCodigo as pedirCodigo, listarDeudas, listarEnRiesgo, resolverDisputa } from "../api/deudas";
import { listarParaDevolver } from "../api/pagos";
import {
  dinero, ESTADO_DEUDA, fecha, hoyEnChile, mesDeHoy, MOTIVOS_DISPUTA, paraBuscar, porcentaje, rutLegible,
  totalDeLaDeuda,
} from "../utils/formato";
import { descargarCsv, montoParaExcel } from "../utils/exportar";
import BarraEstado, { etapaDe } from "../components/BarraEstado";
import { EstadoCartera, RecuperadoPorDia } from "../components/Graficos";
import { IconoCheck, IconoDescargar, IconoFlecha } from "../components/Iconos";
import Paginas, { dePagina } from "../components/Paginas";

/** De a cuantas deudas se ve la cartera (#280). */
const POR_PAGINA = 20;

const FILTROS = ["todas", "open", "repacted", "disputed", "paid", "withdrawn"];

/** Cuantos dias de Chile pasaron desde un instante, o null si no hay. */
function diasDesde(iso) {
  if (!iso) return null;
  const dia = new Date(iso).toLocaleDateString("sv-SE", { timeZone: "America/Santiago" });
  return Math.max(0, Math.round((Date.parse(hoyEnChile()) - Date.parse(dia)) / 86400000));
}

const enDias = (n) => (n === 1 ? "1 día" : `${n} días`);

/**
 * Responder el reclamo de un deudor (#474): lo que dijo, cuanto lleva
 * esperando, y las dos respuestas con lo que pasa con cada una, antes de
 * elegir. La nota queda en el registro de la deuda.
 */
function PanelReclamo({ deuda, ocupado, onResponder, onCancelar }) {
  const [resultado, setResultado] = useState("");
  const [nota, setNota] = useState("");
  const panel = useRef(null);
  const dias = diasDesde(deuda.disputa?.desde);
  const nombre = `reclamo-${deuda.id}`;

  useEffect(() => { panel.current?.focus(); }, []);

  return (
    <form className="panel-reclamo" ref={panel} tabIndex={-1} aria-labelledby={`${nombre}-titulo`}
          onSubmit={(e) => { e.preventDefault(); if (resultado) onResponder(resultado, nota); }}>
      <h3 id={`${nombre}-titulo`}>Reclamo de {deuda.deudor}</h3>
      <p className="hint">
        {deuda.concepto}, ref. {deuda.externalId}
        {dias != null ? ` · ${dias === 0 ? "reclamó hoy" : `espera tu respuesta hace ${enDias(dias)}`}, desde el ${fecha(deuda.disputa.desde)}` : ""}.
        Mientras tanto, esta deuda no se cobra.
      </p>
      <blockquote className="reclamo-cita">
        <b>«{MOTIVOS_DISPUTA[deuda.disputa?.motivo] || deuda.disputa?.motivo}»</b>
        {deuda.disputa?.detalle ? <span className="reclamo-detalle">«{deuda.disputa.detalle}»</span> : null}
      </blockquote>
      <fieldset className="opciones-reclamo">
        <legend>¿Qué respondes?</legend>
        <label className={`opcion${resultado === "reanudar" ? " on" : ""}`}>
          <input type="radio" name={nombre} value="reanudar" checked={resultado === "reanudar"}
                 onChange={() => setResultado("reanudar")} />
          <span>
            <b>La deuda corresponde: vuelve a cobrarse</b>
            <span className="sub">Vuelve a cobranza como estaba, con su convenio si tenía uno, y el deudor la vuelve a ver para pagar.</span>
          </span>
        </label>
        <label className={`opcion${resultado === "retirar" ? " on" : ""}`}>
          <input type="radio" name={nombre} value="retirar" checked={resultado === "retirar"}
                 onChange={() => setResultado("retirar")} />
          <span>
            <b>El deudor tiene razón: se retira</b>
            <span className="sub">Sale de la cobranza, sus cuotas pendientes se anulan y se le avisa al acreedor: no se le vuelve a cobrar.</span>
          </span>
        </label>
      </fieldset>
      <div className="field">
        <label htmlFor={`${nombre}-nota`}>Nota para el registro (opcional)</label>
        <textarea id={`${nombre}-nota`} rows={2} maxLength={500} value={nota} onChange={(e) => setNota(e.target.value)}
                  placeholder="Por ejemplo: el pago de agosto no aparece en nuestros registros" />
      </div>
      <div className="acciones-fila">
        <button type="button" className="btn btn-ghost btn-sm" onClick={onCancelar} disabled={ocupado}>Cancelar</button>
        <button type="submit" className="btn btn-primary btn-sm" disabled={!resultado || ocupado}>
          {ocupado ? <><span className="girando" /> Respondiendo…</> : "Responder"}
        </button>
      </div>
    </form>
  );
}

/**
 * El portal de la empresa que cobra con DataBridge.
 *
 * Los deudores entran en una campana, cargados desde Cargar cartera. Aca se ve
 * en que va cada deuda y se le hace llegar al deudor su codigo de acceso.
 */
export default function DataBridge() {
  const [resumen, setResumen] = useState(null);
  const [comision, setComision] = useState(null);
  const [errorComision, setErrorComision] = useState("");
  const [deudas, setDeudas] = useState([]);
  const [filtro, setFiltro] = useState("todas");
  const [buscar, setBuscar] = useState("");
  const [pagina, setPagina] = useState(0);
  const [avisos, setAvisos] = useState({});
  //  Lo que necesita respuesta (#474): null mientras se consulta, "error" si ese servicio no respondio.
  const [enRiesgo, setEnRiesgo] = useState(null);
  const [duplicados, setDuplicados] = useState(null);
  const [cargada, setCargada] = useState(false);
  //  Desde la bandeja: solo los deudores a los que no les llega el correo.
  const [soloCorreo, setSoloCorreo] = useState(false);
  const [respondiendo, setRespondiendo] = useState(null);
  const listaRef = useRef(null);
  const [error, setError] = useState("");

  async function refrescar() {
    const [r, d] = await Promise.all([resumenDeCartera(), listarDeudas()]);
    setResumen(r);
    setDeudas(d);
    setCargada(true);
  }

  useEffect(() => {
    listarEnRiesgo().then((c) => setEnRiesgo(c.length)).catch(() => setEnRiesgo("error"));
    listarParaDevolver().then((p) => setDuplicados(p.length)).catch(() => setDuplicados("error"));
  }, []);

  useEffect(() => {
    refrescar().catch((err) => setError(err.message));
  }, []);

  useEffect(() => {
    let vigente = true;
    comisionDelMes(mesDeHoy())
      .then((datos) => vigente && setComision(datos))
      .catch(() => vigente && setErrorComision("No se pudo cargar la comisión del mes."));
    return () => { vigente = false; };
  }, []);

  async function enviarCodigo(deuda) {
    setAvisos((a) => ({ ...a, [deuda.id]: { enviando: true } }));
    try {
      const r = await pedirCodigo(deuda.id);
      setAvisos((a) => ({ ...a, [deuda.id]: { ok: `Enviado a ${r.destino}` } }));
    } catch (err) {
      setAvisos((a) => ({ ...a, [deuda.id]: { error: err.message } }));
    }
  }

  /** Revisado el reclamo: la deuda corresponde (reanudar) o no (retirar), desde su panel (#474). */
  async function resolver(deuda, resultado, nota) {
    setAvisos((a) => ({ ...a, [deuda.id]: { enviando: true } }));
    try {
      await resolverDisputa(deuda.id, resultado, nota.trim() || null);
      setAvisos((a) => ({ ...a, [deuda.id]: { ok: resultado === "reanudar" ? "Cobro reanudado" : "Deuda retirada" } }));
      setRespondiendo(null);
      await refrescar();
      //  El numero de reclamos del menu se pone al dia.
      window.dispatchEvent(new Event("cartera-cambio"));
      //  Ya no esta en revision: con ese filtro desapareceria, y no se veria en que quedo.
      setFiltro("todas");
    } catch (err) {
      setAvisos((a) => ({ ...a, [deuda.id]: { error: err.message } }));
    }
  }

  //  La busqueda va primero: los filtros cuentan sobre lo que se busco, y la pagina sobre lo filtrado (#280).
  const buscadas = useMemo(() => {
    const texto = paraBuscar(buscar);
    if (!texto) return deudas;
    return deudas.filter((d) => [d.deudor, d.deudorRut, d.externalId, d.concepto, d.acreedor]
      .some((campo) => paraBuscar(campo).includes(texto)));
  }, [deudas, buscar]);
  const cuantas = useMemo(() => Object.fromEntries(FILTROS.map((f) => [f,
    f === "todas" ? buscadas.length : buscadas.filter((d) => d.estado === f).length])), [buscadas]);
  //  El grafico cuenta toda la cartera, sin la busqueda.
  const porEstado = useMemo(() => deudas.reduce((c, d) => ({ ...c, [d.estado]: (c[d.estado] || 0) + 1 }), {}), [deudas]);
  const reclamos = useMemo(() => deudas.filter((d) => d.estado === "disputed"), [deudas]);
  const sinCorreo = useMemo(() => new Set(deudas.filter((d) => (d.estado === "open" || d.estado === "repacted")
    && (d.correoFallido || d.sinCorreoValido)).map((d) => d.id)), [deudas]);
  const visibles = buscadas.filter((d) => (filtro === "todas" || d.estado === filtro) && (!soloCorreo || sinCorreo.has(d.id)));
  const { enPagina, filas: deEstaPagina } = dePagina(visibles, pagina, POR_PAGINA);

  function filtrar(f) {
    setFiltro(f);
    setSoloCorreo(false);
    setPagina(0);
  }

  /** Desde la bandeja: la cartera filtrada a eso, y a la vista. */
  function verEnLaCartera(f, correo = false) {
    setBuscar("");
    setFiltro(f);
    setSoloCorreo(correo);
    setPagina(0);
    listaRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  //  La bandeja del inicio (#474): solo lo que tiene algo pendiente, con su numero y a donde ir.
  const masAntiguo = reclamos.map((d) => diasDesde(d.disputa?.desde)).filter((n) => n != null).sort((a, b) => b - a)[0];
  const pendientes = [
    reclamos.length > 0 && {
      clave: "reclamos", numero: reclamos.length, accion: () => verEnLaCartera("disputed"),
      titulo: reclamos.length === 1 ? "Un reclamo de un deudor espera tu respuesta" : `${reclamos.length} reclamos de deudores esperan tu respuesta`,
      detalle: reclamos.length === 1
        ? `Lleva ${enDias(masAntiguo ?? 0)}. Mientras tanto, esa deuda no se cobra.`
        : `${masAntiguo != null ? `El más antiguo lleva ${enDias(masAntiguo)}. ` : ""}Mientras tanto, esas deudas no se cobran.`,
    },
    typeof duplicados === "number" && duplicados > 0 && {
      clave: "duplicados", numero: duplicados, enlace: "/databridge/pagos",
      titulo: duplicados === 1 ? "Un pago cobrado dos veces por devolver" : `${duplicados} pagos cobrados dos veces por devolver`,
      detalle: "Registra la devolución en Pagos recibidos cuando le devuelvas el dinero al deudor.",
    },
    typeof enRiesgo === "number" && enRiesgo > 0 && {
      clave: "riesgo", numero: enRiesgo, enlace: "/databridge/en-riesgo",
      titulo: enRiesgo === 1 ? "Un convenio tiene cuotas vencidas" : `${enRiesgo} convenios tienen cuotas vencidas`,
      detalle: "Revísalos en Convenios en riesgo, antes de que se caigan.",
    },
    sinCorreo.size > 0 && {
      clave: "correos", numero: sinCorreo.size, accion: () => verEnLaCartera("todas", true),
      titulo: sinCorreo.size === 1 ? "A un deudor no le llega el correo" : `A ${sinCorreo.size} deudores no les llega el correo`,
      detalle: "Su correo rebotó o no es válido: puedes reenviarle el código o pedirle otro correo al acreedor.",
    },
  ].filter(Boolean);
  const revisando = !cargada || enRiesgo === null || duplicados === null;
  const fallos = [duplicados === "error" && "los pagos por devolver", enRiesgo === "error" && "los convenios en riesgo"].filter(Boolean);

  function exportar() {
    descargarCsv("cartera.csv",
      ["Deudor", "RUT", "Acreedor", "Referencia", "Concepto", "Moneda", "Total", "Pagado", "Saldo", "Estado",
        "Cuotas pagadas", "Cuotas del convenio", "Actualizada"],
      visibles.map((d) => [d.deudor, rutLegible(d.deudorRut), d.acreedor, d.externalId, d.concepto, d.moneda,
        montoParaExcel(totalDeLaDeuda(d), d.moneda), montoParaExcel(d.pagado, d.moneda),
        montoParaExcel(d.saldo, d.moneda), etapaDe(d).texto,
        d.cuotasPagadas, d.cuotasTotales, fecha(d.actualizada)]));
  }

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <span className="eyebrow">{resumen?.organizacion || "DataBridge"}</span>
          <h1>Cartera morosa</h1>
          <p>Deudores en mora, y en qué va cada uno: por pagar, en convenio o pagada.</p>
        </div>
      </header>
      {error ? <div className="error">{error}</div> : null}

      {/* Lo que necesita respuesta, arriba; las cifras y los graficos, debajo (#474, decision del PO). */}
      <section className="card bandeja bloque aparece" style={{ "--i": 1 }} aria-labelledby="bandeja-titulo" aria-busy={revisando}>
        <h2 id="bandeja-titulo">Lo que necesita tu respuesta hoy</h2>
        {revisando ? (
          <p className="hint">Revisando…</p>
        ) : pendientes.length === 0 ? (
          <p className="bandeja-al-dia"><IconoCheck size={18} /> Estás al día: nada espera tu respuesta.</p>
        ) : (
          <ul className="bandeja-lista">
            {pendientes.map((p) => {
              const dentro = (
                <>
                  <b className="bandeja-numero">{p.numero}</b>
                  <span className="bandeja-texto"><b>{p.titulo}</b><span className="sub">{p.detalle}</span></span>
                  <IconoFlecha size={16} />
                </>
              );
              return (
                <li key={p.clave}>
                  {p.enlace ? <Link to={p.enlace} className="bandeja-item">{dentro}</Link>
                    : <button type="button" className="bandeja-item" onClick={p.accion}>{dentro}</button>}
                </li>
              );
            })}
          </ul>
        )}
        {!revisando && fallos.length ? <p className="hint">No se pudo revisar {fallos.join(" ni ")}: inténtalo en un rato.</p> : null}
      </section>

      <section className="grid-3 stats stats-cartera bloque">
        <div className="card stat aparece" style={{ "--i": 1 }}>
          <span>Deudas en gestión</span>
          <b>{resumen?.activas ?? "–"}</b>
          <small>{resumen ? `${resumen.enConvenio} en convenio de pago` : ""}</small>
        </div>
        <div className="card stat aparece" style={{ "--i": 2 }}>
          <span>Deudas pagadas</span>
          <b>{resumen?.pagadas ?? "–"}</b>
          <small>
            {resumen?.retiradas
              ? `Además, ${resumen.retiradas} ${resumen.retiradas === 1 ? "retirada" : "retiradas"} por el acreedor`
              : ""}
          </small>
        </div>
        <div className="card stat aparece" style={{ "--i": 3 }}>
          <span>Recuperado</span>
          <b className="totales">
            {(resumen?.porMoneda || []).map((m) => (
              <span key={m.moneda}>{dinero(m.recuperado, m.moneda)}</span>
            ))}
          </b>
          <small>{(resumen?.porMoneda || []).map((m) => `${porcentaje(m.tasaRecuperacion)} en ${m.moneda === "UF" ? "UF" : "pesos"}`).join(", ")}</small>
          <small>
            Lo que pagas a DataBridge: {errorComision || (comision?.comision != null ? dinero(comision.comision) : "–")}
          </small>
        </div>
      </section>

      <div className="grid-2 bloque aparece" style={{ "--i": 4, alignItems: "stretch" }}>
        <EstadoCartera resumen={resumen} cuentas={porEstado} />
        <RecuperadoPorDia resumen={resumen} />
      </div>

      <div className="card aparece lista-cartera" style={{ "--i": 5 }} ref={listaRef}>
        <div className="card-cab">
          <h2>Deudas</h2>
          <button type="button" className="btn btn-ghost btn-sm" disabled={!visibles.length} onClick={exportar}>
            <IconoDescargar size={16} />
            Exportar a Excel
          </button>
        </div>
        <div className="card-cab">
          <div className="filters">
            {FILTROS.map((f) => (
              <button key={f} type="button" className={`chip ${filtro === f ? "on" : ""}`} aria-pressed={filtro === f}
                      onClick={() => filtrar(f)}>
                {f === "todas" ? "Todas" : ESTADO_DEUDA[f]}
                <span className="chip-cuenta">{cuantas[f]}</span>
              </button>
            ))}
          </div>
          <input className="buscador" type="search" value={buscar} placeholder="Buscar por deudor, RUT o referencia"
                 aria-label="Buscar en la cartera" onChange={(e) => { setBuscar(e.target.value); setPagina(0); }} />
        </div>
        {soloCorreo ? (
          <p className="hint" role="status">
            Solo los deudores a los que no les llega el correo.{" "}
            <button type="button" className="link-btn" onClick={() => filtrar("todas")}>Ver todas</button>
          </p>
        ) : null}
        {visibles.length === 0 ? (
          <div className="empty">
            {deudas.length === 0 ? (
              <>
                Todavía no hay deudores. Se cargan en una campaña, desde{" "}
                <Link className="link-btn" to="/databridge/cargar">Cargar cartera</Link>.
              </>
            ) : buscar.trim() ? "Ninguna deuda calza con la búsqueda." : "No hay deudas en ese estado."}
          </div>
        ) : (
          <div className="tabla-scroll">
            <table className="table tabla-cartera">
              <thead>
                <tr>
                  <th>Deudor</th>
                  <th>Acreedor</th>
                  <th className="num">Saldo</th>
                  <th>Estado</th>
                  <th>Acciones</th>
                </tr>
              </thead>
              <tbody>
                {deEstaPagina.map((d) => {
                  const aviso = avisos[d.id];
                  const cobrable = d.estado === "open" || d.estado === "repacted";
                  const dias = d.estado === "disputed" ? diasDesde(d.disputa?.desde) : null;
                  return (
                    <Fragment key={d.id}>
                    <tr>
                      <td data-campo="Deudor">
                        {d.deudor}<span className="sub">{rutLegible(d.deudorRut)}</span>
                        {d.sinCorreoValido ? <span className="tag tag-vencida">Sin correo válido</span> : null}
                        {d.correoFallido?.conProblemas ? (
                          <span className="tag tag-vencida"
                                title="El servidor rechazó su dirección tres veces seguidas: ya no se le escribe solo hasta que llegue otro correo en la cartera. Puedes reenviarle el código a mano.">
                            Correo con problemas
                          </span>
                        ) : null}
                      </td>
                      <td data-campo="Acreedor">{d.acreedor}<span className="sub">{d.concepto}, ref. {d.externalId}</span></td>
                      <td className="num" data-campo="Saldo">
                        {dinero(d.saldo, d.moneda)}
                        <span className="sub">de {dinero(totalDeLaDeuda(d), d.moneda)}</span>
                        {Number(d.interesMora) > 0 ? (
                          <span className="sub">
                            + {dinero(d.interesMora, d.moneda)} de mora, al {porcentaje(d.tasaInteresMensual)} mensual
                          </span>
                        ) : null}
                      </td>
                      <td data-campo="Estado">
                        <BarraEstado deuda={d} compacta />
                        <span className="sub">{fecha(d.actualizada)}</span>
                        {d.disputa ? (
                          <span className="sub">
                            <b>{MOTIVOS_DISPUTA[d.disputa.motivo] || d.disputa.motivo}</b>
                            {d.disputa.detalle ? `: «${d.disputa.detalle}»` : ""}
                          </span>
                        ) : null}
                        {dias != null ? (
                          <span className="tag tag-vencida">{dias === 0 ? "Reclamó hoy" : `Espera hace ${enDias(dias)}`}</span>
                        ) : null}
                      </td>
                      <td data-campo="Acciones">
                        {d.estado === "disputed" ? (
                          <button type="button" className="btn btn-primary btn-sm" aria-expanded={respondiendo === d.id}
                                  onClick={() => setRespondiendo(respondiendo === d.id ? null : d.id)}>
                            Responder reclamo
                          </button>
                        ) : null}
                        {cobrable ? (
                          <button type="button" className="btn btn-ghost btn-sm" disabled={aviso?.enviando}
                                  onClick={() => enviarCodigo(d)}>
                            {aviso?.enviando ? <><span className="girando" /> Enviando…</>
                              : d.codigoEnviado ? "Reenviar código" : "Enviar código"}
                          </button>
                        ) : null}
                        {cobrable && !aviso ? (
                          <span className="sub">
                            {d.codigoEnviado ? `Último correo el ${fecha(d.codigoEnviado)}` : "Todavía sin invitar"}
                          </span>
                        ) : null}
                        {cobrable && !aviso && d.correoFallido ? (
                          <span className="sub" style={{ color: "var(--danger)" }}>
                            No se pudo enviar el correo del {fecha(d.correoFallido.cuando)}: {d.correoFallido.motivo}
                          </span>
                        ) : null}
                        {aviso?.ok ? <span className="sub">{aviso.ok}</span> : null}
                        {aviso?.error ? <span className="sub" style={{ color: "var(--danger)" }}>{aviso.error}</span> : null}
                      </td>
                    </tr>
                    {respondiendo === d.id ? (
                      <tr className="fila-reclamo">
                        <td colSpan={5}>
                          <PanelReclamo deuda={d} ocupado={aviso?.enviando}
                                        onResponder={(r, nota) => resolver(d, r, nota)}
                                        onCancelar={() => setRespondiendo(null)} />
                        </td>
                      </tr>
                    ) : null}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        <Paginas total={visibles.length} porPagina={POR_PAGINA} pagina={enPagina} alCambiar={setPagina}
                 etiqueta="Páginas de la cartera" />
        <p className="hint">
          Al entrar su deuda, al deudor le llega solo su código de acceso. Con el botón se le reenvía. El código
          va a su correo y no se muestra aquí: quien lo viera podría entrar en su lugar.
        </p>
      </div>
    </div>
  );
}
