import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import Logo from "../components/Logo";
import TemaToggle from "../components/TemaToggle";
import { IconoVolver } from "../components/Iconos";
import { rutLegible } from "../utils/formato";
import { useAuth } from "../store/authStore";

/**
 * Dos portales, dos formas de entrar.
 *
 * El deudor escribe su RUT y el codigo que le llego. No hay enlace que tocar:
 * es lo que evita que un mensaje falso lo lleve a otra parte (docs de APOFYX,
 * seccion 13.3). El enlace al correo existe como respaldo, para quien no
 * recibio el codigo, y es como entra el personal de las empresas. El admin de
 * DataBridge entra igual, con su enlace, por su propia puerta.
 */
export default function Login({ portal }) {
  const esAdmin = portal === "ADMIN";
  const esDeudor = portal !== "DATABRIDGE" && !esAdmin;
  const [modo, setModo] = useState(esDeudor ? "codigo" : "enlace");

  return (
    <div className="auth-shell">
      <TemaToggle flotante />
      <section className="auth-hero">
        <div className="brand-row aparece">
          <Logo size={46} />
          <div>
            <div className="brand-name">{esDeudor ? "Technical Bridge" : "DataBridge"}</div>
            <div className="brand-sub">
              {esDeudor ? "Portal de pago" : esAdmin ? "Panel de administración" : "Portal de empresas"}
            </div>
          </div>
        </div>
        <div className="hero-copy">
          {esDeudor ? (
            <>
              <h1 className="aparece" style={{ "--i": 1 }}>Entra con tu RUT y tu código</h1>
              <p className="aparece" style={{ "--i": 2 }}>
                Sin cuenta y sin contraseña. El código te llegó por correo o WhatsApp de parte de la
                empresa con la que tienes el pago pendiente. Nunca te vamos a mandar un enlace para
                entrar: escribe esta dirección tú mismo.
              </p>
            </>
          ) : esAdmin ? (
            <>
              <h1 className="aparece" style={{ "--i": 1 }}>La plataforma, desde un solo lugar</h1>
              <p className="aparece" style={{ "--i": 2 }}>
                Solo para el equipo de DataBridge. Entras con un enlace a tu correo, sin contraseña.
                Si eres de una empresa, tu entrada es el portal de empresas.
              </p>
            </>
          ) : (
            <>
              <h1 className="aparece" style={{ "--i": 1 }}>La cartera que gestionas, al día</h1>
              <p className="aparece" style={{ "--i": 2 }}>
                Quién debe, quién está en convenio y qué pagos llegaron. Cargas a tus deudores en una
                campaña y DataBridge les escribe, les cobra y te muestra cada pago.
              </p>
            </>
          )}
        </div>
        <Link to="/" className="btn btn-ghost btn-sm volver-link aparece" style={{ "--i": 3 }}>
          <IconoVolver size={16} />
          Volver
        </Link>
      </section>
      <section className="auth-panel">
        <div className="auth-card">
          {modo === "codigo" ? (
            <ConCodigo onSinCodigo={() => setModo("enlace")} />
          ) : (
            <ConEnlace esDeudor={esDeudor} onVolver={esDeudor ? () => setModo("codigo") : null}
                       ejemplo={esAdmin ? "nombre@databridge.cl" : "nombre@empresa.cl"} />
          )}
        </div>
      </section>
    </div>
  );
}

function ConCodigo({ onSinCodigo }) {
  const { entrarConCodigo, homeFor } = useAuth();
  const navigate = useNavigate();
  const [rut, setRut] = useState("");
  const [codigo, setCodigo] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function onSubmit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const user = await entrarConCodigo(rut, codigo);
      navigate(homeFor(user), { replace: true });
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={onSubmit}>
      <h2>Entrar</h2>
      {error ? <div className="error">{error}</div> : null}
      <div className="field">
        <label htmlFor="rut">RUT</label>
        <input
          id="rut"
          value={rut}
          onChange={(e) => setRut(rutLegible(e.target.value))}
          placeholder="12.345.678-5"
          inputMode="text"
          autoComplete="username"
          required
        />
      </div>
      <div className="field">
        <label htmlFor="codigo">Código de acceso</label>
        <input
          id="codigo"
          className="code-input"
          value={codigo}
          onChange={(e) => setCodigo(e.target.value.toUpperCase().replace(/[^0-9A-Z]/g, "").slice(0, 6))}
          placeholder="······"
          autoComplete="one-time-code"
          required
        />
      </div>
      <button className="btn btn-primary btn-block" disabled={busy || codigo.length !== 6}>
        {busy ? <><span className="girando" /> Entrando…</> : "Entrar"}
      </button>
      <p className="hint centro">
        El código sirve una vez y dura 24 horas.{" "}
        <button type="button" className="link-btn" onClick={onSinCodigo}>
          ¿No te llegó?
        </button>
      </p>
    </form>
  );
}

function ConEnlace({ esDeudor, onVolver, ejemplo }) {
  const { pedirEnlace } = useAuth();
  const [correo, setCorreo] = useState("");
  const [rut, setRut] = useState("");
  const [enviado, setEnviado] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function onSubmit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      setEnviado(await pedirEnlace(esDeudor ? null : correo, esDeudor ? rut : null));
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  if (enviado) {
    return (
      <div>
        <h2>Revisa tu correo</h2>
        <p>{enviado.mensaje}</p>
        <p className="hint">
          El enlace sirve una vez y dura {enviado.expiraEnMinutos} minutos.
        </p>
        {import.meta.env.DEV ? (
          <div className="notice">
            En desarrollo los correos llegan al buzón de prueba:{" "}
            <a href="http://localhost:8025" target="_blank" rel="noreferrer">localhost:8025</a>
          </div>
        ) : null}
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit}>
      <h2>{esDeudor ? "Recibir un enlace" : "Entrar"}</h2>
      {esDeudor ? (
        <p className="hint" style={{ margin: "-6px 0 16px" }}>
          Si no te llegó el código, te mandamos un enlace de un solo uso al correo que la empresa
          tiene registrado. Solo necesitamos tu RUT.
        </p>
      ) : null}
      {error ? <div className="error">{error}</div> : null}
      {esDeudor ? (
        <div className="field">
          <label htmlFor="rut">RUT</label>
          <input id="rut" value={rut} onChange={(e) => setRut(rutLegible(e.target.value))}
                 placeholder="12.345.678-5" required />
        </div>
      ) : null}
      {esDeudor ? null : (
        <div className="field">
          <label htmlFor="correo">Correo</label>
          <input
            id="correo"
            type="email"
            value={correo}
            onChange={(e) => setCorreo(e.target.value)}
            placeholder={ejemplo}
            autoComplete="email"
            required
          />
        </div>
      )}
      <button className="btn btn-primary btn-block" disabled={busy}>
        {busy ? <><span className="girando" /> Enviando…</> : "Enviar enlace"}
      </button>
      {onVolver ? (
        <p className="hint centro">
          <button type="button" className="link-btn" onClick={onVolver}>Tengo mi código</button>
        </p>
      ) : null}
    </form>
  );
}
