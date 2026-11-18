import { useEffect, useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import { accesosDeDeudor, fichaDeDeudor, pagosDeDeudor } from "../api/admin";
import Cargando from "../components/Cargando";
import Paginas, { dePagina } from "../components/Paginas";
import { IconoVolver } from "../components/Iconos";
import { dinero, ESTADO_DEUDA, fechaHora, rutLegible } from "../utils/formato";
import { METODOS, RESULTADOS } from "./AdminAccesos";

const CLASE_DE_DEUDA = {
  open: "badge-warn", repacted: "badge-muted", paid: "badge-ok", withdrawn: "badge-muted", disputed: "badge-peligro",
};

const ESTADO_DE_PAGO = {
  created: { texto: "Abierto", clase: "badge-muted" },
  authorized: { texto: "Autorizado", clase: "badge-muted" },
  paid: { texto: "Pagado", clase: "badge-ok" },
  failed: { texto: "Falló", clase: "badge-peligro" },
  expired: { texto: "Vencido", clase: "badge-warn" },
  refunded: { texto: "Devuelto", clase: "badge-muted" },
  duplicated: { texto: "Duplicado", clase: "badge-warn" },
};

const PASARELA = { webpay: "Webpay", khipu: "Khipu", mercadopago: "Mercado Pago" };

/** De a cuantas filas se ve cada parte: un deudor puede tener decenas de pagos e ingresos. */
const POR_PAGINA = 10;

/** Qué correo fue, en palabras. */
function queCorreo(c) {
  if (c.tipo === "invitation") return "Invitación";
  if (c.tipo === "reminder") return "Recordatorio de cuota";
  if (c.tipo === "resend") return "Código reenviado por la empresa";
  if (c.tipo === "campaign") return `Toque ${c.toque ?? ""} de ${c.campana || "su campaña"}`.replace("  ", " ");
  return c.tipo;
}

/**
 * Una parte de la ficha, de a {@link POR_PAGINA} filas. Si se pide a otro
 * servicio y no responde, lo dice, y el resto de la ficha se ve igual.
 */
function Parte({ titulo, id, datos, vacio, tabla }) {
  const [pagina, setPagina] = useState(0);
  const lista = Array.isArray(datos) ? datos : [];
  const { enPagina, filas } = dePagina(lista, pagina, POR_PAGINA);
  return (
    <section className="card bloque aparece" aria-labelledby={id}>
      <h2 id={id}>{titulo}{lista.length ? <span className="cuenta-de-parte"> · {lista.length}</span> : null}</h2>
      {datos === "error" ? (
        <p className="hint" role="status">Ese servicio no respondió. Lo demás de la ficha está al día.</p>
      ) : datos === null ? (
        <Cargando tarjetas={1} />
      ) : lista.length === 0 ? (
        <div className="empty">{vacio}</div>
      ) : (
        <>
          <div className="tabla-scroll">{tabla(filas)}</div>
          <Paginas total={lista.length} porPagina={POR_PAGINA} pagina={enPagina} alCambiar={setPagina}
                   etiqueta={`Páginas de ${titulo.toLowerCase()}`} />
        </>
      )}
    </section>
  );
}

/**
 * La ficha de un deudor en el panel (#434): sus datos, sus deudas en todas las
 * empresas, sus pagos, los correos que se le enviaron y sus pedidos de codigo.
 * Abrirla queda en la bitacora del panel (lo anota ms-debt). Desde aqui no se
 * cambia nada: eso lo hace la empresa.
 */
export default function AdminDeudor() {
  const { rut } = useParams();
  const desde = useLocation().state?.desde || "";
  const [ficha, setFicha] = useState(null);
  const [noExiste, setNoExiste] = useState(false);
  const [error, setError] = useState("");
  const [pagos, setPagos] = useState(null);
  const [ingresos, setIngresos] = useState(null);

  useEffect(() => {
    setFicha(null);
    setNoExiste(false);
    setError("");
    setPagos(null);
    setIngresos(null);
    fichaDeDeudor(rut)
      .then(setFicha)
      .catch((e) => (e.status === 404 ? setNoExiste(true) : setError(e.message)));
    pagosDeDeudor(rut).then(setPagos).catch(() => setPagos("error"));
    accesosDeDeudor(rut).then(setIngresos).catch(() => setIngresos("error"));
  }, [rut]);

  const volver = (
    <Link className="btn btn-ghost btn-sm" to={`/admin/deudores${desde}`} style={{ marginBottom: 14 }}>
      <IconoVolver size={16} /> Deudores
    </Link>
  );

  if (noExiste) {
    return (
      <div>
        {volver}
        <div className="empty bloque">No hay ningún deudor con el RUT {rutLegible(rut)}.</div>
      </div>
    );
  }
  if (error) {
    return (
      <div>
        {volver}
        <div className="error" role="alert">{error}</div>
      </div>
    );
  }
  if (!ficha) return <Cargando tarjetas={3} />;

  const correo = ficha.estadoDelCorreo;

  return (
    <div>
      {volver}
      <header className="topbar aparece">
        <div>
          <h1>{ficha.nombre}</h1>
          <p>
            RUT {rutLegible(ficha.rut)} · {ficha.tipo === "company" ? "Empresa" : "Persona"} ·{" "}
            {ficha.deudas.length} {ficha.deudas.length === 1 ? "deuda" : "deudas"}
          </p>
        </div>
      </header>
      <p className="hint" role="note">Abriste esta ficha: queda en la bitácora del panel.</p>

      <section className="card bloque aparece" aria-labelledby="ficha-contacto">
        <h2 id="ficha-contacto">Contacto</h2>
        <dl className="ficha-datos">
          <div><dt>Correo</dt><dd>{ficha.correo || "Sin correo registrado"}</dd></div>
          <div><dt>Teléfono</dt><dd>{ficha.telefono || "Sin teléfono"}</dd></div>
          <div><dt>Recordatorios de cuotas</dt><dd>{ficha.recordatorios ? "Los recibe" : "No los quiere"}</dd></div>
          <div>
            <dt>Si le llega el correo</dt>
            <dd>
              {correo.yaNoSeLeEscribe ? (
                <span className="badge badge-peligro">Ya no se le escribe solo</span>
              ) : correo.ultimoFallo ? (
                <span className="badge badge-warn">El último no salió</span>
              ) : (
                <span className="badge badge-ok">Le llegan</span>
              )}
              {correo.ultimoFallo ? (
                <span className="sub">{fechaHora(correo.ultimoFallo)}: {correo.motivo}</span>
              ) : null}
              {correo.yaNoSeLeEscribe ? (
                <span className="sub">
                  {correo.rechazosSeguidos} rechazos seguidos de la dirección. Vuelve cuando la empresa cargue otro correo.
                </span>
              ) : null}
            </dd>
          </div>
        </dl>
      </section>

      <Parte titulo="Deudas" id="ficha-deudas" datos={ficha.deudas} vacio="No tiene deudas." tabla={(filas) => (
        <table className="table tabla-ficha">
          <thead><tr><th scope="col">Deuda</th><th scope="col">Saldo</th></tr></thead>
          <tbody>
            {filas.map((d) => (
              <tr key={d.id}>
                <td>
                  <b>{d.referencia}</b> · {d.acreedor}
                  <span className="sub">
                    {d.campana ? `${d.campana} · ` : ""}la cobra {d.cobra}
                    {d.enPausa ? " (suspendida)" : ""}
                  </span>
                  {d.concepto ? <span className="sub">{d.concepto}</span> : null}
                </td>
                <td>
                  <b>{dinero(d.saldo, d.moneda)}</b>
                  <span className="sub">
                    <span className={`badge ${CLASE_DE_DEUDA[d.estado] || "badge-muted"}`}>
                      {ESTADO_DEUDA[d.estado] || d.estado}
                    </span>
                  </span>
                  <span className="sub">
                    {d.cuotasPagadas} de {d.cuotasTotales} cuotas
                    {d.cuotasVencidas ? ` · ${d.cuotasVencidas} vencidas` : ""}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )} />

      <Parte titulo="Pagos" id="ficha-pagos" datos={pagos} vacio="No tiene pagos." tabla={(filas) => (
          <table className="table tabla-ficha">
            <thead><tr><th scope="col">Cuándo</th><th scope="col">Monto</th></tr></thead>
            <tbody>
              {filas.map((p) => (
                <tr key={p.id}>
                  <td>
                    {fechaHora(p.pagado || p.creado)}
                    <span className="sub">
                      {PASARELA[p.pasarela] || p.pasarela} · {p.cuotas} {p.cuotas === 1 ? "cuota" : "cuotas"}
                    </span>
                  </td>
                  <td>
                    <b>{dinero(p.monto, p.moneda)}</b>
                    <span className="sub">
                      <span className={`badge ${ESTADO_DE_PAGO[p.estado]?.clase || "badge-muted"}`}>
                        {ESTADO_DE_PAGO[p.estado]?.texto || p.estado}
                      </span>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
      )} />

      <Parte titulo="Correos enviados" id="ficha-correos" datos={ficha.correosEnviados}
             vacio="Todavía no se le ha escrito." tabla={(filas) => (
        <table className="table tabla-ficha">
          <thead><tr><th scope="col">Cuándo</th><th scope="col">Qué</th></tr></thead>
          <tbody>
            {filas.map((c, i) => (
              <tr key={`${c.cuando}-${i}`}>
                <td>
                  {fechaHora(c.cuando)}
                  {c.destino ? <span className="sub">{c.destino}</span> : null}
                </td>
                <td>
                  {queCorreo(c)}
                  <span className="sub">{c.referencia} · {c.acreedor}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )} />

      <Parte titulo="Pedidos de código e ingresos" id="ficha-ingresos" datos={ingresos}
             vacio="Nunca ha pedido un código ni ha entrado." tabla={(filas) => (
          <table className="table tabla-ficha">
            <thead><tr><th scope="col">Cuándo</th><th scope="col">Resultado</th></tr></thead>
            <tbody>
              {filas.map((a, i) => (
                <tr key={`${a.cuando}-${i}`}>
                  <td>
                    {fechaHora(a.cuando)}
                    <span className="sub">{METODOS[a.metodo] || a.metodo}</span>
                  </td>
                  <td>
                    <span className={`badge ${RESULTADOS[a.resultado]?.clase || "badge-muted"}`}
                          title={RESULTADOS[a.resultado]?.que}>
                      {RESULTADOS[a.resultado]?.texto || a.resultado}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
      )} />
    </div>
  );
}
