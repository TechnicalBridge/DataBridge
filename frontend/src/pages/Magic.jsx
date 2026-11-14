import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useAuth } from "../store/authStore";
import TemaToggle from "../components/TemaToggle";

export default function Magic() {
  const [params] = useSearchParams();
  const token = params.get("token") || "";
  const navigate = useNavigate();
  const { entrarConEnlace, homeFor, ready } = useAuth();
  const [mensaje, setMensaje] = useState("Validando el enlace…");
  const [fallo, setFallo] = useState(false);
  // En desarrollo React monta dos veces; el enlace es de un solo uso y el
  // segundo intento lo encontraria gastado.
  const usado = useRef(false);

  useEffect(() => {
    // Primero termina de resolverse la sesion que pudiera haber: al cargar,
    // la pagina intenta renovarla, y eso es una llamada de red. Si el enlace
    // entrara en paralelo y esa renovacion fallara despues, su resultado
    // pisaria la sesion recien abierta y la persona quedaria afuera. Las
    // paginas de login no tienen el problema: PublicOnly ya las hace esperar.
    if (!ready) return;
    if (usado.current) return;
    usado.current = true;
    if (!token) {
      setMensaje("Al enlace le falta el token.");
      setFallo(true);
      return;
    }
    entrarConEnlace(token)
      .then((user) => navigate(homeFor(user), { replace: true }))
      .catch((err) => {
        setMensaje(err.message);
        setFallo(true);
      });
  }, [token, ready]);

  return (
    <div className="auth-panel" style={{ minHeight: "100vh" }}>
      <TemaToggle flotante />
      <div className="auth-card">
        <h1>Enlace de acceso</h1>
        <p className={fallo ? "error" : "esperando"} style={fallo ? undefined : { justifyContent: "flex-start" }}>
          {fallo ? null : <span className="girando" />}
          {mensaje}
        </p>
        {fallo ? <Link className="btn btn-primary btn-block" to="/login">Volver a entrar</Link> : null}
      </div>
    </div>
  );
}
