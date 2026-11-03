import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { descargarCertificado, disputar, listarDeudas, listarVencimientos } from "../api/deudas";
import { dinero, fechaLarga, hoyEnChile, MOTIVOS_DISPUTA, porcentaje, porMoneda, rutLegible, totalDeLaDeuda } from "../utils/formato";
import { useAuth } from "../store/authStore";
import BarraEstado from "../components/BarraEstado";
import Cargando from "../components/Cargando";
import { CheckAnimado, IconoCalendario, IconoDocumento, IconoFlecha } from "../components/Iconos";

const sePuedePagar = (d) => (d.estado === "open" || d.estado === "repacted") && !d.enPausa;

/** 5 de agosto, con el ano solo si no es este: una deuda puede venir de lejos. */
function diaDe(iso) {
  const ano = iso.slice(0, 4);
  return ano === hoyEnChile().slice(0, 4) ? fechaLarga(iso) : `${fechaLarga(iso)} de ${ano}`;
}

const cuandoVencio = (cuota) => `${cuota.vencida ? "Venció" : "Vence"} el ${diaDe(cuota.vencimiento)}`;

/**
 * Lo primero que le toca pagar al deudor: de las deudas que se pueden pagar, la
 * que vencio primero (los vencimientos llegan en ese orden). El monto es el que
 * muestra Pagar al llegar, con las mismas cuentas, pero es solo un aviso: al
 * cobrar lo decide ms-debt.
 */
function loQueToca(deudas, cuotas) {
  const pagables = deudas.filter(sePuedePagar);
  if (!pagables.length) return null;
  const primera = cuotas.find((c) => pagables.some((d) => d.id === c.deudaId));
  const deuda = pagables.find((d) => d.id === primera?.deudaId) || pagables[0];
  const suyas = cuotas.filter((c) => c.deudaId === deuda.id);
  const pagar = `/app/pagar/${deuda.id}`;

  //  Fallaron los vencimientos: sin saber que cuotas tocan, no se dice un monto que Pagar no va a mostrar.
  if (deuda.conConvenio && !suyas.length) {
    return { deuda, titulo: "Lo que toca ahora", monto: null, boton: "Pagar cuotas", destino: pagar };
  }

  //  Sin convenio se paga todo de una vez, y ahi corre el descuento por pronto pago.
  if (!deuda.conConvenio) {
    return {
      deuda,
      titulo: "Lo que toca ahora",
      monto: Number(deuda.totalHoy) - Number(deuda.descuentoDisponible || 0),
      detalle: suyas.length ? cuandoVencio(suyas[0]) : null,
      descuento: Number(deuda.descuentoDisponible || 0),
      boton: "Pagar",
      destino: pagar,
    };
  }

  //  En un convenio, la mora es solo de las cuotas vencidas: ponerse al dia la paga entera.
  const vencidas = suyas.filter((c) => c.vencida);
  if (vencidas.length) {
    const n = vencidas.length;
    return {
      deuda,
      titulo: "Lo que toca ahora",
      monto: vencidas.reduce((suma, c) => suma + Number(c.monto), 0) + Number(deuda.interesMora || 0),
      detalle: n === 1 ? `Cuota vencida el ${diaDe(vencidas[0].vencimiento)}`
        : `${n} cuotas vencidas, desde el ${diaDe(vencidas[0].vencimiento)}`,
      boton: n === 1 ? "Pagar cuota" : "Pagar cuotas",
      destino: n === 1 ? pagar : `${pagar}?cuotas=${n}`,
    };
  }

  const siguiente = suyas[0];
  return {
    deuda,
    titulo: "Tu próximo pago",
    monto: Number(siguiente.monto),
    detalle: `${siguiente.lugar ? `Cuota ${siguiente.lugar} de ${siguiente.deCuotas} · ` : ""}${cuandoVencio(siguiente)}`,
    boton: "Pagar cuota",
    destino: pagar,
  };
}

export default function Debts() {
  const { user } = useAuth();
  const [deudas, setDeudas] = useState(null);
  const [cuotas, setCuotas] = useState([]);
  const [error, setError] = useState("");

  function cargar() {
    //  Sin los vencimientos la pantalla sigue igual: solo falta la fecha de lo que toca.
    return Promise.all([listarDeudas(), listarVencimientos().catch(() => [])])
      .then(([lista, porVencer]) => {
        setDeudas(lista);
        setCuotas(porVencer);
      })
      .catch((err) => {
        setError(err.status === 404 ? "" : err.message);
        setDeudas([]);
      });
  }

  useEffect(() => {
    cargar();
  }, []);

  if (deudas === null) return <Cargando />;

  const nombre = deudas[0]?.deudor?.split(" ")[0];
  const toca = loQueToca(deudas, cuotas);
  const orden = toca ? [toca.deuda, ...deudas.filter((d) => d !== toca.deuda)] : deudas;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <span className="eyebrow">RUT {rutLegible(user?.rut)}</span>
          <h1>{nombre ? `Hola, ${nombre}` : "Hola"}</h1>
        </div>
      </header>
      {error ? <div className="error">{error}</div> : null}

      {toca ? <TocaAhora toca={toca} deudas={deudas} /> : <NadaQuePagar deudas={deudas} />}

      {deudas.length ? (
        <section className="deudas">
          {orden.map((d, i) => <TarjetaDeuda key={d.id} deuda={d} i={i + 2} onCambio={cargar} />)}
        </section>
      ) : null}
    </div>
  );
}

function deudasEnPalabras(n) {
  return n === 1 ? "1 deuda" : `${n} deudas`;
}

/** Arriba de todo: cuanto, a quien y el boton. Debajo, en chico, lo demas, solo si hay algo. */
function TocaAhora({ toca, deudas }) {
  const { deuda } = toca;
  const otras = deudas.filter((d) => sePuedePagar(d) && d !== deuda);
  const pagadas = deudas.filter((d) => d.estado === "paid").length;
  //  Pesos y UF no se suman entre si.
  const totalOtras = porMoneda(otras, "totalHoy").map(([moneda, total]) => dinero(total, moneda)).join(" y ");

  return (
    <>
      <section className="card ahora aparece" style={{ "--i": 1 }} aria-labelledby="toca-ahora">
        <div>
          <span className="eyebrow">{toca.titulo}</span>
          <h2 id="toca-ahora">
            {toca.monto === null ? `Tu convenio con ${deuda.acreedor}`
              : <><b>{dinero(toca.monto, deuda.moneda)}</b> a {deuda.acreedor}</>}
          </h2>
          <p>{deuda.concepto}{toca.detalle ? ` · ${toca.detalle}` : ""}</p>
          {toca.descuento > 0 ? (
            <p>
              Ya tiene {dinero(toca.descuento, deuda.moneda)} de descuento, por pagar todo
              {deuda.descuentoHasta ? ` antes del ${diaDe(deuda.descuentoHasta)}` : " ahora"}.
            </p>
          ) : null}
        </div>
        <Link className="btn btn-primary" to={toca.destino}>
          {toca.boton}
          <IconoFlecha size={16} />
        </Link>
      </section>
      {otras.length || pagadas ? (
        <p className="hint ahora-mas aparece" style={{ "--i": 1 }}>
          {otras.length ? `Tienes ${deudasEnPalabras(otras.length)} más: ${totalOtras}. ` : ""}
          {pagadas ? `Ya pagaste ${deudasEnPalabras(pagadas)}.` : ""}
        </p>
      ) : null}
    </>
  );
}

/** Sin nada que se pueda pagar hoy: lo dice y explica por que, sin boton. */
function NadaQuePagar({ deudas }) {
  const enRevision = deudas.some((d) => d.estado === "disputed");
  const enPausa = deudas.some((d) => (d.estado === "open" || d.estado === "repacted") && d.enPausa);

  if (enRevision || enPausa) {
    return (
      <section className="card ahora aparece" style={{ "--i": 1 }} aria-labelledby="toca-ahora">
        <div>
          <h2 id="toca-ahora">Por ahora no hay nada que pagar</h2>
          <p>
            {enRevision && enPausa ? "Tienes una deuda en revisión y otra con la cobranza en pausa. En cada una te contamos qué pasa."
              : enRevision ? "Se está revisando lo que nos dijiste. Mientras tanto no se cobra ni te llegan recordatorios."
                : "La cobranza está en pausa. Cuando se reanude, vas a poder pagar aquí."}
          </p>
        </div>
      </section>
    );
  }

  const pagadas = deudas.filter((d) => d.estado === "paid").length;
  return (
    <div className="card al-dia bloque aparece" style={{ "--i": 1 }}>
      <CheckAnimado size={64} />
      <h2>Estás al día</h2>
      <p>
        {deudas.length === 0 ? "No hay deudas a tu nombre en DataBridge."
          : pagadas ? `Ya pagaste ${deudasEnPalabras(pagadas)}.` : "No tienes deudas por pagar."}
      </p>
    </div>
  );
}

function TarjetaDeuda({ deuda: d, i, onCambio }) {
  const pagada = d.estado === "paid";
  const cobrable = d.estado === "open" || d.estado === "repacted";
  //  La empresa que la cobra esta suspendida: no se paga ni se repacta, pero se puede reclamar (#152).
  const enPausa = !!d.enPausa;
  const abonado = Number(d.pagado) > 0 && !pagada;
  const [disputando, setDisputando] = useState(false);

  return (
    <article className="card deuda lift aparece" style={{ "--i": i }}>
      <div className="deuda-cab">
        <div>
          <span className="eyebrow">{d.acreedor}</span>
          <h2>{d.concepto}</h2>
          <span className="sub">Ref. {d.externalId}</span>
          {d.estado === "repacted" && d.cuotasVencidas > 0 ? (
            <span className="tag tag-vencida" style={{ marginTop: 8 }}>
              {d.cuotasVencidas === 1 ? "Una cuota vencida" : `${d.cuotasVencidas} cuotas vencidas`}
            </span>
          ) : null}
        </div>
        <div className="deuda-monto">
          <span>{pagada ? "Pagaste" : "Saldo"}</span>
          <b>{dinero(pagada ? d.pagado : d.totalHoy ?? d.saldo, d.moneda)}</b>
          {abonado ? <small>de {dinero(totalDeLaDeuda(d), d.moneda)}</small> : null}
          {!pagada && Number(d.interesMora) > 0 ? (
            <small>
              Incluye {dinero(d.interesMora, d.moneda)} de intereses por mora ({porcentaje(d.tasaInteresMensual)}{" "}
              mensual)
            </small>
          ) : null}
        </div>
      </div>

      {!pagada && Number(d.descuentoDisponible) > 0 ? (
        <div className="oferta">
          <b>
            Si pagas todo{d.descuentoHasta ? ` antes del ${fechaLarga(d.descuentoHasta)}` : " ahora"}, te
            descontamos {dinero(d.descuentoDisponible, d.moneda)} de intereses.
          </b>
          <span>Pagarías {dinero(Number(d.totalHoy) - Number(d.descuentoDisponible), d.moneda)} en vez de {dinero(d.totalHoy, d.moneda)}.</span>
        </div>
      ) : null}

      <BarraEstado deuda={d} />

      {d.estado === "disputed" ? (
        <div className="aviso-revision">
          <b>En revisión: {MOTIVOS_DISPUTA[d.disputa?.motivo] || "lo que nos dijiste"}</b>
          <span>
            {d.acreedor} está revisando tu caso. Mientras tanto esta deuda no se cobra ni te llegan recordatorios.
          </span>
        </div>
      ) : null}

      {enPausa ? (
        <div className="aviso-revision">
          <b>Por ahora no se puede pagar</b>
          <span>La cobranza de esta deuda está en pausa. Mientras tanto no se cobra ni te llegan correos; cuando se reanude, vas a poder pagarla aquí.</span>
        </div>
      ) : null}

      {disputando ? (
        <FormularioDisputa deuda={d} onCancelar={() => setDisputando(false)}
                           onListo={() => { setDisputando(false); onCambio(); }} />
      ) : null}

      <div className="deuda-acciones">
        {pagada ? (
          <button className="btn btn-soft btn-sm" type="button" onClick={() => descargarCertificado(d.id)}>
            <IconoDocumento size={16} />
            Certificado de pago
          </button>
        ) : null}
        {d.estado === "open" && !enPausa ? (
          <Link className="btn btn-ghost btn-sm" to={`/app/repactar/${d.id}`}>
            <IconoCalendario size={16} />
            Pagar en cuotas
          </Link>
        ) : null}
        {cobrable && !disputando ? (
          <button type="button" className="link-btn" onClick={() => setDisputando(true)}>
            No reconozco esta deuda
          </button>
        ) : null}
        {cobrable && !enPausa ? (
          <Link className="btn btn-primary btn-sm" to={`/app/pagar/${d.id}`}>
            {d.estado === "repacted" ? "Pagar cuotas" : "Pagar"}
            <IconoFlecha size={16} />
          </Link>
        ) : null}
      </div>
    </article>
  );
}

/**
 * Decir que una deuda no corresponde. La empresa que cobra la revisa, y
 * mientras tanto no se cobra. Lo que se escribe aca lo ve solo esa empresa.
 */
function FormularioDisputa({ deuda, onCancelar, onListo }) {
  const [motivo, setMotivo] = useState("");
  const [detalle, setDetalle] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function enviar(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await disputar(deuda.id, motivo, detalle.trim() || null);
      onListo();
    } catch (err) {
      setError(err.message);
      setBusy(false);
    }
  }

  return (
    <form className="disputa" onSubmit={enviar}>
      <b>¿Por qué no corresponde?</b>
      <div className="disputa-motivos">
        {Object.entries(MOTIVOS_DISPUTA).map(([codigo, texto]) => (
          <label key={codigo} className={`chip ${motivo === codigo ? "on" : ""}`}>
            <input type="radio" name={`motivo-${deuda.id}`} value={codigo} checked={motivo === codigo}
                   onChange={() => setMotivo(codigo)} />
            {texto}
          </label>
        ))}
      </div>
      <textarea rows={3} maxLength={500} value={detalle} onChange={(e) => setDetalle(e.target.value)}
                placeholder="Si quieres, cuéntanos más: cuándo pagaste, qué monto debería ser…" />
      {error ? <div className="error">{error}</div> : null}
      <div className="deuda-acciones">
        <button type="button" className="btn btn-ghost btn-sm" onClick={onCancelar} disabled={busy}>Cancelar</button>
        <button type="submit" className="btn btn-primary btn-sm" disabled={busy || !motivo}>
          {busy ? <><span className="girando" /> Enviando…</> : "Enviar a revisión"}
        </button>
      </div>
    </form>
  );
}
