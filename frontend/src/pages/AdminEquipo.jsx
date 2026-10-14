import { useCallback, useEffect, useState } from "react";
import { crearAdmin, devolverAcceso, historialDeAdmins, listarAdmins, quitarAcceso } from "../api/admin";
import { useAuth } from "../store/authStore";
import Cargando from "../components/Cargando";
import { FormPersona, Historial, TablaPersonas } from "../components/AdminPersonas";

/**
 * El equipo de DataBridge: quienes son admin. Un admin le da acceso a otro, y
 * se lo puede quitar; no a si mismo, para que siempre quede uno que pueda
 * entrar.
 */
export default function AdminEquipo() {
  const { user } = useAuth();
  const [admins, setAdmins] = useState(null);
  const [acciones, setAcciones] = useState([]);
  const [aviso, setAviso] = useState("");
  const [error, setError] = useState("");

  const cargar = useCallback(async () => {
    const [equipo, historia] = await Promise.all([listarAdmins(), historialDeAdmins()]);
    setAdmins(equipo);
    setAcciones(historia);
  }, []);

  useEffect(() => {
    cargar().catch((err) => {
      setError(err.message);
      setAdmins([]);
    });
  }, [cargar]);

  async function agregar(persona) {
    setAviso("");
    setError("");
    await crearAdmin(persona);
    setAviso(`A ${persona.correo} le llegó su invitación para entrar a este panel.`);
    await cargar();
  }

  async function cambiarAcceso(persona, accion) {
    if (accion === "quitar"
        && !window.confirm(`¿Quitarle el acceso al panel a ${persona.nombre}? Sus sesiones se cierran.`)) {
      return;
    }
    setAviso("");
    setError("");
    try {
      await (accion === "quitar" ? quitarAcceso(persona.id) : devolverAcceso(persona.id));
      await cargar();
    } catch (err) {
      setError(err.message);
    }
  }

  if (admins === null) return <Cargando tarjetas={2} />;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Equipo de DataBridge</h1>
          <p>Quiénes entran a este panel. Un admin es solo de DataBridge: una persona de una empresa no puede serlo.</p>
        </div>
      </header>
      {error ? <div className="error" role="alert">{error}</div> : null}
      {aviso ? <div className="notice" role="status">{aviso}</div> : null}

      <div className="grid-2">
        <div>
          <div className="card bloque aparece" style={{ "--i": 1 }}>
            <h3>Admins</h3>
            <TablaPersonas personas={admins} tuCorreo={user?.correo} alCambiar={cambiarAcceso}
                           vacio="No hay ningún admin." />
          </div>
          <div className="card aparece" style={{ "--i": 3 }}>
            <h3>Lo que se hizo desde el panel</h3>
            <Historial acciones={acciones} />
          </div>
        </div>

        <div className="card aparece" style={{ "--i": 2 }}>
          <h3>Dar de alta a otro admin</h3>
          <FormPersona prefijo="a" boton="Dar de alta" alGuardar={agregar} ejemplo="nombre@databridge.cl" />
          <p className="hint">
            Va a poder dar de alta empresas y personas, igual que tú. Le llega un enlace a su correo, y entra sin
            contraseña. No puedes quitarte el acceso a ti mismo: eso lo hace otro admin.
          </p>
        </div>
      </div>
    </div>
  );
}
