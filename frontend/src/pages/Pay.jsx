import { useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { obtenerDeuda } from "../api/deudas";
import { abrirCobro, cancelarPago, obtenerPago } from "../api/pagos";
import { dinero, fecha, fechaLarga, hora, hoyEnChile, porcentaje } from "../utils/formato";
import Cargando from "../components/Cargando";
import LogoPasarela, { PASARELAS, nombreDePasarela } from "../components/LogoPasarela";
import Tablones, { avanceEnPalabras, tablonesDe } from "../components/Tablones";
import { CheckAnimado, IconoCandado, IconoCheck, IconoFlecha, IconoVolver } from "../components/Iconos";


const porVencimiento = (a, b) => a.vencimiento.localeCompare(b.vencimiento) || a.numero - b.numero;

/**
 * Pagar una deuda, o las cuotas de su convenio.
 *
 * Las cuotas se eligen EN ORDEN, desde la que vence primero: marcar la
 * tercera marca tambien la primera y la segunda, y desmarcar la segunda
 * desmarca las que siguen. ms-debt exige lo mismo, porque pagar diciembre
 * dejando octubre impago dejaria al deudor en mora con plata pagada.
 *
 * El monto no lo manda la pantalla: ms-payments se lo pregunta a ms-debt. Si
 * lo mandara el navegador, bastaria editar la peticion para pagar un peso.
 */
export default function Pay() {
  const { id } = useParams();
  const [parametros] = useSearchParams();
  const { state, pathname, search } = useLocation();
  const navigate = useNavigate();
  //  Recien aceptado un convenio (#473): se avisa una vez, y no vuelve a salir al recargar.
  const [convenio] = useState(() => state?.convenio || null);
  const [deuda, setDeuda] = useState(null);
  const [pasarela, setPasarela] = useState("webpay");
  //  Desde Mis deudas llegan marcadas las cuotas vencidas. Solo es lo que parte marcado: el
  //  deudor lo puede cambiar, y al cobrar ms-debt revisa las cuotas y decide el monto.
  const [cuantas, setCuantas] = useState(() => Math.max(1, Number.parseInt(parametros.get("cuotas"), 10) || 1));
  //  La lista de cuotas muestra las proximas 3; el resto, a pedido (#556).
  const [verTodas, setVerTodas] = useState(false);
  const [pago, setPago] = useState(null);
  // La ventana de la pasarela, para traerla al frente en vez de abrirla de nuevo.
  const ventana = useRef(null);
  //  Se cerro la ventana de la pasarela con el cobro abierto: se ofrece cancelarlo (#307).
  const [ventanaCerrada, setVentanaCerrada] = useState(false);
  const [cancelando, setCancelando] = useState(false);
  const [errorCancelar, setErrorCancelar] = useState("");
  const [aviso, setAviso] = useState("");
  const [acreditado, setAcreditado] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const esperandoRef = useRef(null);

  useEffect(() => {
    obtenerDeuda(id).then(setDeuda).catch((err) => setError(err.message));
  }, [id]);

  //  En el telefono la barra fija se va al abrir el cobro (#475): lo que sigue (esperar, abrir la ventana de
  //  nuevo o cancelar) se trae a la vista, para no tener que buscarlo al volver de la pasarela.
  useEffect(() => {
    if (pago?.id && window.matchMedia?.("(max-width: 980px)").matches) {
      esperandoRef.current?.scrollIntoView({ block: "center", behavior: "smooth" });
    }
  }, [pago?.id]);

  useEffect(() => {
    if (state?.convenio) navigate(`${pathname}${search}`, { replace: true, state: null });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Mientras la pasarela no confirma, se consulta el pago. Fallido (Khipu lo
  // rechazo, o el deudor lo anulo) o vencido, no hay nada mas que esperar. El
  // enlace a la pasarela solo viene al abrir el cobro: se conserva.
  useEffect(() => {
    if (!pago || ["paid", "failed", "expired", "duplicated"].includes(pago.status)) return;
    //  Solo se ofrece, no se cancela solo: un pago hecho justo antes de cerrar la ventana se confirma igual,
    //  y hay pasarelas que hacen ver cerrada una ventana que sigue abierta.
    const mirarVentana = () => {
      if (ventana.current?.closed) setVentanaCerrada(true);
    };
    const t = setInterval(async () => {
      mirarVentana();
      try {
        const nuevo = await obtenerPago(pago.id);
        setPago((antes) => ({ ...nuevo, checkoutUrl: antes?.checkoutUrl }));
      } catch {
        /* se reintenta en el proximo ciclo */
      }
    }, 2000);
    window.addEventListener("focus", mirarVentana);
    return () => {
      clearInterval(t);
      window.removeEventListener("focus", mirarVentana);
    };
  }, [pago?.id, pago?.status]);

  // Confirmado el pago, el aviso tarda unos segundos en llegar a la deuda.
  useEffect(() => {
    if (pago?.status !== "paid" || acreditado) return;
    const saldoAntes = Number(deuda?.saldo);
    const t = setInterval(async () => {
      const nueva = await obtenerDeuda(id).catch(() => null);
      if (nueva && Number(nueva.saldo) < saldoAntes) {
        setDeuda(nueva);
        setAcreditado(true);
      }
    }, 2000);
    return () => clearInterval(t);
  }, [pago?.status, acreditado]);

  if (!deuda) return error ? <div className="error">{error}</div> : <Cargando tarjetas={1} />;

  if (deuda.estado === "disputed") {
    return (
      <div className="card" style={{ maxWidth: 560 }}>
        <h2>Esta deuda está en revisión</h2>
        <p className="hint">
          Nos dijiste que no corresponde, y {deuda.acreedor} lo está revisando. Mientras tanto no se cobra: si la
          empresa confirma que corresponde, vuelve a aparecer para pagar.
        </p>
        <Link className="btn btn-ghost btn-sm" to="/app"><IconoVolver size={16} /> Volver a mis deudas</Link>
      </div>
    );
  }

  //  La empresa que la cobra esta suspendida (#152). Un pago que ya estaba en curso se sigue mostrando.
  if (deuda.enPausa && !pago) {
    return (
      <div className="card" style={{ maxWidth: 560 }}>
        <h2>Por ahora esta deuda no se puede pagar</h2>
        <p className="hint">La cobranza de esta deuda está en pausa. Mientras tanto no se cobra ni te llegan correos; cuando se reanude, vas a poder pagarla aquí.</p>
        <Link className="btn btn-ghost btn-sm" to="/app"><IconoVolver size={16} /> Volver a mis deudas</Link>
      </div>
    );
  }

  const moneda = deuda.moneda;
  const vigentes = (deuda.cuotas || []).filter((c) => c.estado !== "anulada").sort(porVencimiento);
  const pendientes = vigentes.filter((c) => c.estado === "pending");
  const pagadas = vigentes.filter((c) => c.estado === "paid");
  //  Las cuotas se numeran corrido entre planes (la 1 pudo quedar anulada al
  //  repactar): al deudor se le muestra su lugar dentro del convenio. Un mes
  //  que el acreedor informo despues va aparte y no corre la numeracion.
  const delPlan = vigentes.filter((c) => c.enConvenio);
  const lugar = (c) => (c.enConvenio ? `Cuota ${delPlan.indexOf(c) + 1} de ${delPlan.length}` : "Fuera del convenio");
  const enConvenio = deuda.conConvenio && pendientes.length > 0;
  const k = Math.min(cuantas, pendientes.length);
  const elegidas = enConvenio ? pendientes.slice(0, k) : pendientes;
  //  Con tasa, cada cuota vencida se paga con su mora de hoy: ms-debt la suma al cobrar.
  const capital = elegidas.reduce((suma, c) => suma + Number(c.monto), 0);
  const mora = elegidas.reduce((suma, c) => suma + Number(c.interesMora || 0), 0);
  //  El descuento por pronto pago es solo para el pago de toda la deuda, sin convenio: ms-debt lo descuenta al cobrar.
  const descuento = !enConvenio ? Number(deuda.descuentoDisponible || 0) : 0;
  const monto = capital + mora - descuento;
  const hoy = hoyEnChile();

  //  Los tablones del convenio (#547): verdes las pagadas, marcadas las que se eligen ahora, terracota las
  //  vencidas que quedan sin elegir. Un mes que el acreedor informo despues no es parte del plan.
  const estadosDelPlan = delPlan.map((c) => (c.estado === "paid" ? "pagado" : elegidas.includes(c) ? "marcado"
    : c.vencimiento < hoy ? "vencido" : ""));
  const pagadasDelPlan = delPlan.filter((c) => c.estado === "paid").length;
  const quedarian = delPlan.filter((c) => c.estado !== "paid" && !elegidas.includes(c)).length;
  const textoDelPlan = `Pagaste ${pagadasDelPlan} de ${delPlan.length}. `
    + (k === 0 ? "Elige desde la que vence primero."
      : `Con ${k === 1 ? "esta cuota" : `estas ${k}`}, ${quedarian === 0 ? "quedas al día con el convenio"
        : quedarian === 1 ? "te quedaría 1 por pagar" : `te quedarían ${quedarian} por pagar`}.`);

  /** Marcar la cuota i marca todas las anteriores; desmarcarla, todas las que siguen. */
  const alternar = (i) => setCuantas(i < k ? i : i + 1);
  //  Para elegir sin recorrer la lista (#556): las vencidas son las primeras pendientes, y la lista se corta en 3.
  const vencidas = pendientes.filter((c) => c.vencimiento < hoy).length;
  const visibles = verTodas ? pendientes.length : Math.min(pendientes.length, 3);
  const marcadasOcultas = Math.max(0, k - visibles);
  const ultimaPagada = pagadas.reduce((ultima, c) => (!ultima || (c.pagadaEn || "") > (ultima.pagadaEn || "") ? c : ultima), null);

  async function pagar() {
    setBusy(true);
    setError("");
    setAviso("");
    setErrorCancelar("");
    setVentanaCerrada(false);
    // Se abre la ventana de inmediato en el evento del usuario para que el navegador no la bloquee.
    // Sin "noopener": con esa opcion window.open devuelve null y quedaba una ventana en blanco
    // mientras el portal se iba a la pasarela. El opener se corta a mano.
    const popup = window.open("", "_blank", "width=480,height=720");
    if (popup) popup.opener = null;
    ventana.current = popup;
    try {
      const cuotas = enConvenio ? elegidas.map((c) => c.id) : null;
      const data = await abrirCobro(Number(id), cuotas, pasarela);
      setPago(data);
      if (popup) {
        popup.location.href = data.checkoutUrl;
      } else {
        window.location.href = data.checkoutUrl;
      }
    } catch (err) {
      if (popup) popup.close();
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  /**
   * Volver a la pasarela. Si su ventana sigue abierta, se trae al frente. Si se
   * cerro: Khipu y Mercado Pago dejan abrir su pagina de nuevo, pero en Webpay
   * cada pago sirve una sola vez (si no, Transbank responde con el Error 21),
   * asi que se abre un pago nuevo.
   */
  function abrirDeNuevo() {
    const abierta = ventana.current;
    if (abierta && !abierta.closed) {
      abierta.focus();
      return;
    }
    if (pago?.gateway === "webpay") {
      pagar();
      return;
    }
    ventana.current = window.open(pago.checkoutUrl, "_blank", "width=480,height=720");
    if (ventana.current) {
      ventana.current.opener = null;
      setVentanaCerrada(false);
    }
  }

  /**
   * Cancelar este intento para volver a elegir (#307). ms-payments le pregunta
   * antes a la pasarela: si alcanzo a pagarse, vuelve pagado y se muestra asi; si
   * la esta verificando o no responde, vuelve un error y el intento sigue abierto.
   */
  async function cancelarIntento() {
    setCancelando(true);
    setErrorCancelar("");
    try {
      const nuevo = await cancelarPago(pago.id);
      if (nuevo.status === "expired" || nuevo.status === "failed") {
        //  El portal no puede cerrarla: se le corto el opener al abrirla, para que la pasarela no toque esta pagina.
        const sigueAbierta = ventana.current && !ventana.current.closed;
        ventana.current = null;
        setVentanaCerrada(false);
        setPago(null);
        setAviso(`Cancelaste el pago en ${nombreDePasarela(nuevo.gateway)}. No se te cobró nada: puedes elegir de nuevo.`
          + (sigueAbierta ? " Cierra la ventana de la pasarela, que sigue abierta: ese cobro ya no sirve." : ""));
      } else {
        setPago((antes) => ({ ...nuevo, checkoutUrl: antes?.checkoutUrl }));
      }
    } catch (err) {
      setErrorCancelar(err.message);
    } finally {
      setCancelando(false);
    }
  }

  //  En el telefono, el total y Pagar van en una barra fija sobre la navegacion mientras no hay un cobro abierto.
  const conBarraFija = !pago;

  return (
    <div className={conBarraFija ? "con-pagar-fija" : undefined}>
      <header className="topbar aparece">
        <div>
          <h1>Pagar</h1>
          <p>
            {deuda.concepto} con {deuda.acreedor}, ref. {deuda.externalId}
          </p>
          {/* La CAE, dicha en palabras donde aparece (#558). */}
          {deuda.conConvenio && deuda.cae != null ? (
            <p className="hint">
              CAE {porcentaje(deuda.cae, 2)}: la carga anual equivalente, lo que te cuesta el convenio en un año con
              sus intereses.
            </p>
          ) : null}
          {Object.entries(deuda.referencias || {}).length ? (
            <p className="hint">
              Referencias: {Object.entries(deuda.referencias).map(([clave, valor]) => `${clave}: ${valor}`).join(" · ")}
            </p>
          ) : null}
        </div>
        <Link className="btn btn-ghost btn-sm" to="/app">
          <IconoVolver size={16} />
          Volver
        </Link>
      </header>
      {error ? <div className="error">{error}</div> : null}
      {convenio ? (
        <div className="notice aparece" role="status">
          <b>Quedó tu convenio de {convenio.cuotas} {convenio.cuotas === 1 ? "cuota" : "cuotas"}</b>
          {convenio.primera ? `: la primera vence el ${fecha(convenio.primera)}.` : "."}
        </div>
      ) : null}

      {pago?.status === "paid" ? (
        <div className="card success aparece">
          <CheckAnimado />
          <h2>{acreditado ? "Pago confirmado" : "Pago recibido"}</h2>
          <p>
            {dinero(pago.amount, pago.currency)}
            {pago.currency === "UF" && pago.amountClp ? `, ${dinero(pago.amountClp)} al valor de hoy` : ""}
          </p>
          <Tablones estados={tablonesDe(deuda)} texto={avanceEnPalabras(deuda)} />
          {acreditado ? (
            <p className="hint">
              {Number(deuda.saldo) > 0
                ? `Tu saldo quedó en ${dinero(deuda.saldo, moneda)}. ${deuda.acreedor} ya fue avisado. `
                  + "El comprobante te llega al correo y queda en tu historial de pagos."
                : `Pagaste todo. ${deuda.acreedor} ya fue avisado. `
                  + "El comprobante y el certificado de deuda pagada te llegan al correo."}
            </p>
          ) : (
            <p className="esperando"><span className="girando" /> Confirmando el pago con tu deuda…</p>
          )}
          <Link className="btn btn-primary" to="/app" style={{ marginTop: 16 }}>Ver mis deudas</Link>
        </div>
      ) : (
        <>
          <div className="grid-2 pagar-grid">
            <div className="card pegado aparece" style={{ "--i": 1 }}>
              <h2>Resumen</h2>
              <div className="total-pagar">
                <div>
                  <span>Total a pagar</span>
                  {enConvenio ? (
                    <small>{k === 0 ? "Ninguna cuota elegida" : k === 1 ? "1 cuota" : `${k} cuotas`}</small>
                  ) : null}
                </div>
                <b key={`${k}-${monto}`}>{dinero(monto, moneda)}</b>
              </div>
              {mora > 0 && descuento > 0 ? (
                <p className="hint" style={{ marginTop: -6 }}>
                  {dinero(capital, moneda)} de capital y {dinero(mora - descuento, moneda)} de intereses por mora: te
                  descontamos {dinero(descuento, moneda)} por pagar todo de una vez.
                </p>
              ) : mora > 0 ? (
                <p className="hint" style={{ marginTop: -6 }}>
                  {dinero(capital, moneda)} de capital y {dinero(mora, moneda)} de intereses por mora, al{" "}
                  {porcentaje(deuda.tasaInteresMensual)} mensual que pactaste con {deuda.acreedor}.
                </p>
              ) : null}
              <div className="methods" role="radiogroup" aria-label="Medio de pago">
                {Object.entries(PASARELAS).map(([id, p]) => (
                  <button key={id} type="button" role="radio" aria-checked={pasarela === id}
                          className={`method${pasarela === id ? " on" : ""}`}
                          disabled={!!pago} onClick={() => setPasarela(id)} aria-label={p.nombre}>
                    <LogoPasarela id={id} alto={24} />
                    <span>{p.detalle}</span>
                  </button>
                ))}
              </div>
              <button className="btn btn-primary btn-block pagar-en-tarjeta" disabled={busy || monto <= 0 || !!pago}
                      onClick={pagar}>
                {busy ? <><span className="girando" /> Abriendo…</> : <>Pagar {dinero(monto, moneda)} <IconoFlecha size={16} /></>}
              </button>
              {pago?.status === "failed" || pago?.status === "expired" ? (
                <div className="error">
                  El pago no se completó en {nombreDePasarela(pago.gateway)}:{" "}
                  {pago.status === "expired" ? "pasó el plazo para pagarlo" : "se rechazó o lo anulaste"}. No se te
                  cobró nada.{" "}
                  <button type="button" className="link-btn" onClick={() => setPago(null)}>Intentar de nuevo</button>
                </div>
              ) : pago?.status === "duplicated" ? (
                <div className="error">
                  Estas cuotas ya estaban pagadas con otro pago, así que este no se abonó. Queda marcado para
                  devolución: la empresa te devolverá el dinero en {nombreDePasarela(pago.gateway)}.
                </div>
              ) : pago ? (
                <div className="esperando" aria-live="polite" ref={esperandoRef}>
                  {ventanaCerrada ? (
                    <span className="ventana-cerrada">
                      <b>¿Cerraste la ventana de pago?</b> Si no alcanzaste a pagar, cancela este intento y elige de
                      nuevo. Si ya pagaste, espera: se confirma solo.
                    </span>
                  ) : (
                    <>
                      <span className="girando" />
                      Esperando la confirmación de la pasarela…{" "}
                    </>
                  )}
                  <button type="button" className="link-btn" onClick={abrirDeNuevo} disabled={busy || cancelando}>
                    Abrirla de nuevo
                  </button>
                  <button type="button" className={ventanaCerrada ? "btn btn-soft btn-sm" : "link-btn"}
                          onClick={cancelarIntento} disabled={busy || cancelando}>
                    {cancelando ? "Revisando con la pasarela…" : "Cancelar y elegir de nuevo"}
                  </button>
                  {pago.venceA && (
                    <span className="hint"> Puedes pagar hasta las {hora(pago.venceA)}.</span>
                  )}
                  {errorCancelar ? <div className="error" role="alert">{errorCancelar}</div> : null}
                </div>
              ) : (
                <>
                  {aviso ? <div className="notice aviso-cancelado" role="status">{aviso}</div> : null}
                  <p className="hint centro" style={{ display: "flex", gap: 6, justifyContent: "center", alignItems: "center" }}>
                    <IconoCandado size={14} />
                    {moneda === "UF" ? "Se cobra en pesos, al valor de la UF de hoy." : "Pago seguro: el monto sale de tu deuda registrada."}
                  </p>
                </>
              )}
            </div>
            <div className="card pagar-detalle aparece" style={{ "--i": 2 }}>
              {enConvenio ? (
                <>
                  <h2>¿Cuántas cuotas pagas hoy?</h2>
                  {delPlan.length > 1 ? <Tablones estados={estadosDelPlan} texto={textoDelPlan} /> : null}
                  {pendientes.length > 1 ? (
                    <div className="cuantas">
                      <div className="cuantas-paso" role="group" aria-label="Cuántas cuotas pagas hoy">
                        <button type="button" className="cuantas-boton" aria-label="Una cuota menos"
                                disabled={!!pago || k <= 1} onClick={() => setCuantas(k - 1)}>−</button>
                        <output className="cuantas-numero" aria-live="polite">
                          <b>{k}</b> de {pendientes.length}
                        </output>
                        <button type="button" className="cuantas-boton" aria-label="Una cuota más"
                                disabled={!!pago || k >= pendientes.length} onClick={() => setCuantas(k + 1)}>+</button>
                      </div>
                      <div className="atajos">
                        <button type="button" className={`chip${k === 1 ? " on" : ""}`} disabled={!!pago}
                                onClick={() => setCuantas(1)}>
                          La próxima
                        </button>
                        {vencidas > 1 && vencidas < pendientes.length ? (
                          <button type="button" className={`chip${k === vencidas ? " on" : ""}`} disabled={!!pago}
                                  onClick={() => setCuantas(vencidas)}>
                            Ponerme al día ({vencidas})
                          </button>
                        ) : null}
                        <button type="button" className={`chip${k === pendientes.length ? " on" : ""}`}
                                disabled={!!pago} onClick={() => setCuantas(pendientes.length)}>
                          Todas ({pendientes.length})
                        </button>
                      </div>
                    </div>
                  ) : null}
                  <p className="hint" style={{ margin: "14px 0" }}>
                    Se pagan en orden, desde la que vence primero.
                  </p>
                  <div className="cuotas-lista">
                    {ultimaPagada ? (
                      <div className="cuota-fila pagada">
                        <span className="casilla" aria-hidden="true"><IconoCheck size={14} /></span>
                        <span className="cuota-info">
                          <b>Ya pagaste {pagadas.length === 1 ? "1 cuota" : `${pagadas.length} cuotas`}</b>
                          {ultimaPagada.pagadaEn ? (
                            <span>{pagadas.length === 1 ? "El" : "La última, el"} {fecha(ultimaPagada.pagadaEn)}</span>
                          ) : null}
                        </span>
                      </div>
                    ) : null}
                    {pendientes.slice(0, visibles).map((c, i) => (
                      <label key={c.id} className={`cuota-fila${i < k ? " elegida" : ""}`}
                             style={{ "--i": i + (ultimaPagada ? 1 : 0) }}>
                        <input type="checkbox" className="oculto" checked={i < k} disabled={!!pago}
                               onChange={() => alternar(i)} />
                        <span className="casilla" aria-hidden="true"><IconoCheck size={14} /></span>
                        <span className="cuota-info">
                          <b>{lugar(c)}</b>
                          <span>
                            Vence el {fecha(c.vencimiento)}
                            {c.vencimiento < hoy ? <span className="tag tag-vencida">Vencida</span> : null}
                          </span>
                        </span>
                        <span className="cuota-monto">
                          {dinero(c.monto, moneda)}
                          {Number(c.interesMora) > 0 ? <small>+ {dinero(c.interesMora, moneda)} de mora</small> : null}
                        </span>
                      </label>
                    ))}
                  </div>
                  {visibles < pendientes.length ? (
                    <button type="button" className="link-btn cuotas-mas" onClick={() => setVerTodas(true)}>
                      Ver las demás ({pendientes.length - visibles}
                      {marcadasOcultas ? `, ${marcadasOcultas === 1 ? "1 marcada" : `${marcadasOcultas} marcadas`}` : ""})
                    </button>
                  ) : null}
                </>
              ) : (
                <>
                  <h2>Lo que debes</h2>
                  <ul className="detalle-pago">
                    {(deuda.cargos || []).map((c, i) => (
                      <li key={i}>
                        <span>{c.concepto}<small>Venció el {fecha(c.vencimiento)}</small></span>
                        <b>{dinero(c.monto, moneda)}</b>
                      </li>
                    ))}
                    {mora > 0 ? (
                      <li>
                        <span>Intereses por mora<small>Al {porcentaje(deuda.tasaInteresMensual)} mensual</small></span>
                        <b>{dinero(mora, moneda)}</b>
                      </li>
                    ) : null}
                    {descuento > 0 ? (
                      <li className="descuento">
                        <span>
                          Descuento por pronto pago
                          {deuda.descuentoHasta ? <small>Si pagas antes del {fechaLarga(deuda.descuentoHasta)}</small> : null}
                        </span>
                        <b>−{dinero(descuento, moneda)}</b>
                      </li>
                    ) : null}
                  </ul>
                  {deuda.estado === "open" ? (
                    <p className="hint">
                      ¿Mucho de una vez?{" "}
                      <Link className="link-btn" to={`/app/repactar/${deuda.id}`}>
                        {deuda.tasaInteresMensual ? "Págalo en cuotas" : "Págalo en cuotas sin interés"}
                      </Link>
                      {descuento > 0 ? " En cuotas no hay descuento: es solo para quien paga todo de una vez." : null}
                    </p>
                  ) : null}
                </>
              )}
            </div>

          </div>
          {conBarraFija ? (
            <div className="pagar-fija" role="region" aria-label="Total y pago">
              <div className="pagar-fija-total">
                <span>{enConvenio ? (k === 1 ? "1 cuota" : `${k} cuotas`) : "Total a pagar"}</span>
                <b>{dinero(monto, moneda)}</b>
              </div>
              <button type="button" className="btn btn-primary" disabled={busy || monto <= 0} onClick={pagar}>
                {busy ? <><span className="girando" /> Abriendo…</> : <>Pagar con {nombreDePasarela(pasarela)} <IconoFlecha size={16} /></>}
              </button>
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}
