import { useEffect, useRef, useState } from "react";
import {
  SERVICIOS, avisosAEmpresas, cargarUf, estadoDe, operacionDePagos, reintentarAviso, reintentarAvisoDePago,
  reintentarDestino,
} from "../api/admin";
import { fechaHora, hoyEnChile } from "../utils/formato";
import { IconoAlerta, IconoCheck } from "../components/Iconos";

/** Cuanto espera la pantalla antes de volver a mirar los avisos que puso en cola: el despachador pasa cada 15 s. */
const DESPUES_DEL_DESPACHADOR_MS = 17000;

const ESTADO_AVISO = {
  pending: { texto: "En cola", clase: "badge-wait" },
  failed: { texto: "Fallido", clase: "badge-peligro" },
};

const QUE_SE_HIZO = {
  aviso_reintentado: (a) => `Reintentó ${a.sobre}`,
  uf_cargada: (a) => `Cargó la UF del ${a.sobre}`,
};

/** El tipo de un aviso (repactacion.aceptada) se puede cortar despues del punto, para caber a 390 px. */
const conCortes = (tipo) => tipo.split(".").flatMap((parte, i) => (i ? [".", <wbr key={i} />, parte] : [parte]));

const ufLegible = (valor) => Number(valor).toLocaleString("es-CL", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/**
 * La operacion de DataBridge (#139): si cada servicio responde, los avisos que
 * no se entregaron (con el boton para reintentarlos), los pagos que hay que
 * mirar y la UF de hoy, con un formulario para cargarla a mano. Cada bloque se
 * pide aparte: si un servicio no responde, su bloque lo dice y los demas
 * siguen. Ningun bloque muestra un dato de un deudor ni el contenido de un aviso.
 */
export default function AdminOperacion() {
  const [estados, setEstados] = useState({});
  const [avisos, setAvisos] = useState(null);
  const [pagos, setPagos] = useState(null);
  const [vuelta, setVuelta] = useState(0);
  const [mensaje, setMensaje] = useState("");
  const [ocupado, setOcupado] = useState("");
  const espera = useRef(null);

  useEffect(() => {
    setEstados({});
    SERVICIOS.forEach(({ tramo }) => {
      estadoDe(tramo)
        .then((r) => setEstados((e) => ({ ...e, [tramo]: r.ok ? "ok" : "caido" })))
        .catch(() => setEstados((e) => ({ ...e, [tramo]: "caido" })));
    });
    avisosAEmpresas().then(setAvisos).catch(() => setAvisos("caido"));
    operacionDePagos().then(setPagos).catch(() => setPagos("caido"));
  }, [vuelta]);

  useEffect(() => () => clearTimeout(espera.current), []);

  /** Pone en cola, avisa, y vuelve a mirar cuando el despachador ya paso. */
  async function enCola(clave, accion, texto) {
    setOcupado(clave);
    setMensaje("");
    try {
      await accion();
      setMensaje(`${texto} El despachador lo intenta en los próximos 15 segundos; esta pantalla se actualiza sola.`);
      setVuelta((v) => v + 1);
      clearTimeout(espera.current);
      espera.current = setTimeout(() => setVuelta((v) => v + 1), DESPUES_DEL_DESPACHADOR_MS);
    } catch (e) {
      setMensaje(e.message);
    } finally {
      setOcupado("");
    }
  }

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Operación</h1>
          <p>
            Lo que no se está entregando o cobrando, para arreglarlo antes de que una empresa reclame.
            Sin datos de ningún deudor.
          </p>
        </div>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setVuelta((v) => v + 1)}>
          Actualizar
        </button>
      </header>

      {mensaje ? <div className="notice" role="status">{mensaje}</div> : null}

      <div className="grid-2">
        <section className="card aparece" style={{ "--i": 1 }} aria-labelledby="op-servicios">
          <h3 id="op-servicios">Servicios</h3>
          <div className="lista">
            {SERVICIOS.map(({ tramo, nombre, servicio }) => (
              <div className="fila" key={tramo}>
                {estados[tramo] === "caido" ? <IconoAlerta size={20} /> : <IconoCheck size={20} />}
                <div className="fila-que"><b>{nombre}</b><span>{servicio}</span></div>
                <Estado valor={estados[tramo]} />
              </div>
            ))}
          </div>
        </section>

        <section className="card aparece" style={{ "--i": 2 }} aria-labelledby="op-pagos">
          <h3 id="op-pagos">Pagos</h3>
          {pagos === "caido" ? <Caido servicio="ms-payments" que="los pagos y la UF" /> : !pagos ? (
            <p className="hint">Consultando…</p>
          ) : (
            <div className="cifras-revision">
              <div className="cifra">
                <b>{pagos.pagos.abiertos}</b>
                <span>abiertos{pagos.pagos.abiertosHaceMasDeUnaHora ? ` · ${pagos.pagos.abiertosHaceMasDeUnaHora} hace más de una hora` : ""}</span>
              </div>
              <div className="cifra"><b>{pagos.pagos.vencidosUltimas24Horas}</b><span>vencidos en 24 horas</span></div>
              <div className={`cifra${pagos.pagos.duplicados ? " rechazo" : ""}`}>
                <b>{pagos.pagos.duplicados}</b><span>duplicados por devolver</span>
              </div>
            </div>
          )}
          <p className="hint">
            Un pago abierto espera que la pasarela lo confirme; algunos están en verificación.
          </p>
        </section>
      </div>

      <section className="card bloque aparece" style={{ "--i": 3 }} aria-labelledby="op-avisos">
        <h3 id="op-avisos">Avisos a las empresas</h3>
        {avisos === "caido" ? <Caido servicio="ms-debt" que="los avisos a las empresas" /> : !avisos ? (
          <p className="hint">Consultando…</p>
        ) : avisos.destinos.length === 0 ? (
          <div className="empty">Todos los avisos a las empresas se entregaron.</div>
        ) : (
          avisos.destinos.map((d) => (
            <div className="destino-avisos" key={d.suscripcion}>
              <div className="card-cab">
                <div>
                  <b>{d.empresa}</b>
                  <span className="sub">{d.direccion}</span>
                </div>
                <div className="acciones-fila">
                  {d.fallidos ? <span className="badge badge-peligro">{d.fallidos} fallidos</span> : null}
                  {d.pendientes ? <span className="badge badge-wait">{d.pendientes} en cola</span> : null}
                  {d.fallidos ? (
                    <button type="button" className="btn btn-ghost btn-sm" disabled={!!ocupado}
                            onClick={() => enCola(`d${d.suscripcion}`, () => reintentarDestino(d.suscripcion),
                              `Los ${d.fallidos} avisos fallidos a ${d.empresa} quedaron en cola.`)}>
                      {ocupado === `d${d.suscripcion}` ? "Poniendo en cola…" : "Reintentar los fallidos"}
                    </button>
                  ) : null}
                </div>
              </div>
              <TablaAvisos
                avisos={d.avisos}
                primeraColumna={(a) => <><code>{conCortes(a.tipo)}</code><span className="sub">{fechaHora(a.ocurrio)}</span></>}
                titulo="Qué y cuándo"
                ocupado={ocupado}
                alReintentar={(a) => enCola(`a${a.id}`, () => reintentarAviso(a.id), "El aviso quedó en cola.")}
                clave={(a) => `a${a.id}`}
              />
            </div>
          ))
        )}

        <h4 className="lista-grupo">De los pagos a ms-debt</h4>
        {pagos === "caido" ? <Caido servicio="ms-payments" que="los avisos de pago" /> : !pagos ? <p className="hint">Consultando…</p>
          : pagos.avisos.lista.length === 0 ? (
            <div className="empty">ms-debt recibió todos los avisos de pago.</div>
          ) : (
            <TablaAvisos
              avisos={pagos.avisos.lista}
              primeraColumna={(a) => <>Pago {a.pago}</>}
              titulo="Pago"
              ocupado={ocupado}
              alReintentar={(a) => enCola(`p${a.id}`, () => reintentarAvisoDePago(a.id), "El aviso del pago quedó en cola.")}
              clave={(a) => `p${a.id}`}
            />
          )}
      </section>

      <div className="grid-2">
        <section className="card aparece" style={{ "--i": 4 }} aria-labelledby="op-uf">
          <h3 id="op-uf">UF de hoy</h3>
          {pagos === "caido" ? <Caido servicio="ms-payments" que="la UF" /> : !pagos ? (
            <p className="hint">Consultando…</p>
          ) : (
            <>
              {pagos.uf.cargada ? (
                <p className="uf-de-hoy">
                  <b>${ufLegible(pagos.uf.valor)}</b>
                  <span className="sub">
                    {pagos.uf.dia} · {pagos.uf.fuente === "manual" ? "cargada a mano" : "del Banco Central"}
                  </span>
                </p>
              ) : (
                <div className="aviso" role="alert">
                  <IconoAlerta size={20} className="icono" />
                  <div><b>Falta la UF de hoy ({pagos.uf.dia}).</b> Sin ella, los cobros en UF están detenidos.</div>
                </div>
              )}
              <FormUf alCargar={(dia, valor) => {
                setMensaje("");
                return cargarUf(dia, valor).then((r) => {
                  setMensaje(`La UF del ${r.dia} quedó en $${ufLegible(r.valor)}: es la que usan los cobros en UF de ese día.`);
                  setVuelta((v) => v + 1);
                });
              }} />
            </>
          )}
        </section>

        <section className="card aparece" style={{ "--i": 5 }} aria-labelledby="op-hecho">
          <h3 id="op-hecho">Lo último que se hizo con los pagos</h3>
          {pagos === "caido" ? <Caido servicio="ms-payments" que="las acciones con los pagos" /> : !pagos ? (
            <p className="hint">Consultando…</p>
          ) : pagos.acciones.length ? (
            <div className="lista">
              {pagos.acciones.map((a, i) => (
                <div className="fila" key={`${a.accion}-${a.cuando}-${i}`}>
                  <div className="fila-que">
                    <b>{(QUE_SE_HIZO[a.accion] || (() => a.accion))(a)}</b>
                    <span>{a.quien} · {fechaHora(a.cuando)}</span>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="empty">Todavía no se ha hecho nada desde aquí.</div>
          )}
          <p className="hint">Un aviso reintentado a una empresa queda también en la historia de esa empresa.</p>
        </section>
      </div>
    </div>
  );
}

function TablaAvisos({ avisos, primeraColumna, titulo, ocupado, alReintentar, clave }) {
  return (
    <div className="tabla-scroll">
      <table className="table">
        <thead>
          <tr><th scope="col">{titulo}</th><th scope="col">Estado y último error</th><th /></tr>
        </thead>
        <tbody>
          {avisos.map((a) => (
            <tr key={clave(a)}>
              <td>{primeraColumna(a)}</td>
              <td>
                <span className={`badge ${ESTADO_AVISO[a.estado]?.clase || "badge-muted"}`}>
                  {ESTADO_AVISO[a.estado]?.texto || a.estado}
                </span>
                <span className="sub">
                  {a.intentos} {a.intentos === 1 ? "intento" : "intentos"}
                  {a.proximoIntento ? ` · otro a las ${fechaHora(a.proximoIntento).split(", ").pop()}` : ""}
                </span>
                {a.ultimoError ? <span className="error-de-aviso">{a.ultimoError}</span> : null}
              </td>
              <td className="accion-de-aviso">
                {a.estado === "failed" ? (
                  <button type="button" className="btn btn-ghost btn-sm" disabled={!!ocupado} onClick={() => alReintentar(a)}>
                    {ocupado === clave(a) ? "…" : "Reintentar"}
                  </button>
                ) : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function FormUf({ alCargar }) {
  const [dia, setDia] = useState(hoyEnChile());
  const [valor, setValor] = useState("");
  const [error, setError] = useState("");
  const [guardando, setGuardando] = useState(false);

  async function enviar(e) {
    e.preventDefault();
    //  Se escribe como en Chile (40.123,45) o con punto decimal (40123.45).
    const numero = valor.trim().includes(",") ? valor.trim().replace(/\./g, "").replace(",", ".") : valor.trim();
    if (!/^\d+(\.\d{1,2})?$/.test(numero) || Number(numero) <= 0) {
      setError("Escribe la UF como 40.123,45");
      return;
    }
    setError("");
    setGuardando(true);
    try {
      await alCargar(dia, numero);
      setValor("");
    } catch (err) {
      setError(err.message);
    } finally {
      setGuardando(false);
    }
  }

  return (
    <form onSubmit={enviar} className="form-uf">
      <h4 className="lista-grupo">Cargarla a mano</h4>
      <p className="hint" style={{ marginTop: 0 }}>
        Para cuando el Banco Central no la entregó. Si ese día ya tenía valor, lo reemplaza, y queda anotado quién la cargó.
      </p>
      {error ? <div className="error" role="alert">{error}</div> : null}
      <div className="campos-2">
        <div className="field">
          <label htmlFor="uf-dia">Día</label>
          <input id="uf-dia" type="date" value={dia} onChange={(e) => setDia(e.target.value)} required />
        </div>
        <div className="field">
          <label htmlFor="uf-valor">Valor</label>
          <input id="uf-valor" inputMode="decimal" placeholder="40.123,45" value={valor}
                 onChange={(e) => setValor(e.target.value)} required />
        </div>
      </div>
      <button className="btn btn-primary btn-block" disabled={guardando || !dia || !valor.trim()}>
        {guardando ? <><span className="girando" /> Guardando…</> : "Cargar la UF"}
      </button>
    </form>
  );
}

function Estado({ valor }) {
  if (valor === "ok") return <span className="badge badge-ok">Responde</span>;
  if (valor === "caido") return <span className="badge badge-peligro">No responde</span>;
  return <span className="badge badge-muted">Consultando…</span>;
}

function Caido({ servicio, que }) {
  return (
    <p className="hint" role="status">
      <span className="badge badge-peligro">No responde</span>{" "}
      {servicio} no responde: {que} se ven cuando vuelva. Lo demás de esta pantalla sigue al día.
    </p>
  );
}
