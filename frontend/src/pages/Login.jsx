import { useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import Logo from "../components/Logo";
import TemaToggle from "../components/TemaToggle";
import { IconoVolver } from "../components/Iconos";
import Puente from "../components/Puente";
import { rutLegible } from "../utils/formato";
import { useAuth } from "../store/authStore";

/** Los pasos de la portada (#536): en la entrada, el deudor va en el segundo (#539). */
const PASOS = ["Te llega un código", "Entras con tu RUT", "Ves lo que debes", "Pagas como te acomode"];

/**
 * La entrada de los tres portales. Continua el puente de la portada (#539): el deudor ve los cuatro pasos con el
 * segundo marcado; la empresa y el admin, el puente sin los pasos, que son del deudor.
 */
export default function Login({ portal }) {
  const esAdmin = portal === "ADMIN";
  const esDeudor = portal !== "DATABRIDGE" && !esAdmin;
  return (
    <div className="auth-shell">
      <TemaToggle flotante />
      <section className="auth-hero">
        <div className="brand-row aparece">
          <Logo size={46} />
          <div>
            <div className="brand-name">DataBridge</div>
            <div className="brand-sub">
              {esDeudor ? "Portal de pago" : esAdmin ? "Panel de administración" : "Portal de empresas"}
            </div>
          </div>
        </div>
        <div className="hero-copy">
          <h1 className="aparece" style={{ "--i": 1 }}>
            {esDeudor ? "Entra con tu RUT y tu código" : esAdmin ? "El equipo de DataBridge" : "La cartera que gestionas, al día"}
          </h1>
          <p className="aparece" style={{ "--i": 2 }}>
            {esDeudor ? "Tus pagos pendientes y convenios, en un solo lugar. Sin cuenta y sin contraseña."
              : esAdmin ? "Solo para el equipo de DataBridge. Si eres de una empresa, tu entrada es el portal de empresas."
                : "Quién debe, quién está en convenio y qué pagos llegaron."}
          </p>
        </div>
        {esDeudor ? (
          <div className="entrada-pasos aparece" style={{ "--i": 3 }}>
            <ol className="tablones" aria-label="Los cuatro pasos">
              {PASOS.map((paso, i) => (
                <li key={paso} className={i < 2 ? "tablon encendido" : "tablon"} aria-current={i === 1 ? "step" : undefined}>
                  <b>{paso}</b>
                  {i === 1 ? <span className="entrada-aqui">Estás aquí</span> : null}
                </li>
              ))}
            </ol>
            <p className="entrada-paso-corto">Paso 2 de 4: entras con tu RUT</p>
          </div>
        ) : null}
        <Link to="/" className="btn btn-ghost btn-sm volver-link aparece" style={{ "--i": 4 }}>
          <IconoVolver size={16} /> Volver
        </Link>
        <Puente className="puente puente-entrada" />
      </section>
      <section className="auth-panel">
        <div className="auth-card">
          <ConCodigo key={portal || "DEUDOR"} esDeudor={esDeudor} esAdmin={esAdmin} />
          {esDeudor ? (
            <p className="hint centro"><Link to="/deshacer-correo">¿Te cambiaron el correo sin pedirlo?</Link></p>
          ) : null}
        </div>
      </section>
    </div>
  );
}

function ConCodigo({ esDeudor, esAdmin }) {
  const { entrarConCodigo, pedirCodigo, pedirEnlace, homeFor } = useAuth();
  const navigate = useNavigate();
  const entrada = useRef(null);
  const [identificador, setIdentificador] = useState("");
  const [codigo, setCodigo] = useState("");
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState("");
  const [enviado, setEnviado] = useState(null);

  async function solicitar(respaldo) {
    if (!entrada.current.reportValidity()) return;
    setBusy(respaldo ? "respaldo" : "codigo");
    setError("");
    setEnviado(null);
    try {
      const pedir = respaldo ? pedirEnlace : pedirCodigo;
      const respuesta = await pedir(esDeudor ? null : identificador, esDeudor ? identificador : null);
      setCodigo("");
      setEnviado(respuesta);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(null);
    }
  }

  async function entrar(e) {
    e.preventDefault();
    setBusy("entrar");
    setError("");
    try {
      const user = await entrarConCodigo(identificador, codigo, esDeudor);
      navigate(homeFor(user), { replace: true });
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(null);
    }
  }

  return (
    <form onSubmit={entrar} aria-busy={Boolean(busy)}>
      <h2>Entrar</h2>
      {error ? <div className="error" role="alert">{error}</div> : null}
      <div className="field">
        <label htmlFor="identificador">{esDeudor ? "RUT" : "Correo"}</label>
        <input ref={entrada} id="identificador" type={esDeudor ? "text" : "email"}
               value={identificador} disabled={Boolean(busy)}
               onChange={(e) => {
                 setIdentificador(esDeudor ? rutLegible(e.target.value) : e.target.value);
                 setEnviado(null);
                 setCodigo("");
               }}
               placeholder={esDeudor ? "12.345.678-5" : esAdmin ? "nombre@databridge.cl" : "nombre@empresa.cl"}
               autoComplete="username" maxLength={esDeudor ? 20 : 254} required />
      </div>
      <button type="button" className="btn btn-ghost btn-block" disabled={Boolean(busy)}
              onClick={() => solicitar(false)}>
        {busy === "codigo" ? <><span className="girando" /> Enviando…</> : "Enviar código"}
      </button>
      {enviado ? (
        <div className="notice" role="status" style={{ marginTop: 16 }}>
          <p style={{ margin: 0 }}>{enviado.mensaje}</p>
          <p className="hint" style={{ marginBottom: 0 }}>Vence en {enviado.expiraEnMinutos} minutos y sirve una sola vez.</p>
        </div>
      ) : null}
      <div className="field" style={{ marginTop: 20 }}>
        <label htmlFor="codigo">Código de acceso</label>
        <input id="codigo" className="code-input" value={codigo} disabled={Boolean(busy)}
               onChange={(e) => setCodigo(e.target.value.toUpperCase().replace(/[^0-9A-Z]/g, "").slice(0, 6))}
               placeholder="······" autoComplete="one-time-code" minLength={6} maxLength={6} required />
      </div>
      <button className="btn btn-primary btn-block" disabled={Boolean(busy) || codigo.length !== 6}>
        {busy === "entrar" ? <><span className="girando" /> Entrando…</> : "Entrar"}
      </button>
      <p className="hint centro">
        <button type="button" className="link-btn" disabled={Boolean(busy)} onClick={() => solicitar(true)}>
          {busy === "respaldo" ? "Enviando respaldo…" : "¿No te llegó?"}
        </button>
      </p>
    </form>
  );
}
