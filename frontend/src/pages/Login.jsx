import { useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import Logo from "../components/Logo";
import TemaToggle from "../components/TemaToggle";
import { IconoVolver } from "../components/Iconos";
import { rutLegible } from "../utils/formato";
import { useAuth } from "../store/authStore";

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
        <Link to="/" className="btn btn-ghost btn-sm volver-link aparece" style={{ "--i": 3 }}>
          <IconoVolver size={16} /> Volver
        </Link>
      </section>
      <section className="auth-panel">
        <div className="auth-card">
          <ConCodigo key={portal || "DEUDOR"} esDeudor={esDeudor} esAdmin={esAdmin} />
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
