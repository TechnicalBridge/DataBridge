import { useEffect, useState } from "react";
import { NavLink, Outlet } from "react-router-dom";
import { listarEnRiesgo } from "../api/deudas";
import { rutLegible } from "../utils/formato";
import { useAuth } from "../store/authStore";
import Logo from "./Logo";
import Chatbot from "./Chatbot";
import TemaToggle from "./TemaToggle";
import {
  IconoAlerta, IconoAyuda, IconoCalendario, IconoCandado, IconoCartera, IconoDocumento, IconoEmpresa, IconoEquipo,
  IconoMegafono, IconoPanel, IconoRecibo, IconoReglas, IconoSalir, IconoSubir, IconoUsuario,
} from "./Iconos";

/** Cada enlace con su nombre largo (el menu) y el corto (la barra del telefono). */
const DEUDOR = [
  { to: "/app", label: "Mis deudas", corto: "Deudas", Icono: IconoDocumento, end: true },
  { to: "/app/vencimientos", label: "Próximos vencimientos", corto: "Cuotas", Icono: IconoCalendario },
  { to: "/app/pagos", label: "Historial de pagos", corto: "Pagos", Icono: IconoRecibo },
  { to: "/app/mis-datos", label: "Mis datos", corto: "Mis datos", Icono: IconoUsuario },
  { to: "/app/como-funciona", label: "Cómo funciona", corto: "Ayuda", Icono: IconoAyuda, aparte: true },
];

const EMPRESA = [
  { to: "/databridge", label: "Cartera", corto: "Cartera", Icono: IconoCartera, end: true },
  { to: "/databridge/pagos", label: "Pagos recibidos", corto: "Pagos", Icono: IconoRecibo },
  { to: "/databridge/en-riesgo", label: "Convenios en riesgo", corto: "En riesgo", Icono: IconoAlerta, riesgo: true },
  { to: "/databridge/cargar", label: "Cargar cartera", corto: "Cargar", Icono: IconoSubir },
  { to: "/databridge/campanas", label: "Campañas", corto: "Campañas", Icono: IconoMegafono },
  { to: "/databridge/como-funciona", label: "Cómo funciona", corto: "Ayuda", Icono: IconoAyuda, aparte: true },
];

//  El panel de administracion de DataBridge: lo ve solo su admin.
const ADMIN = [
  { to: "/admin", label: "Inicio", corto: "Inicio", Icono: IconoPanel, end: true },
  { to: "/admin/empresas", label: "Empresas", corto: "Empresas", Icono: IconoEmpresa },
  { to: "/admin/operacion", label: "Operación", corto: "Operación", Icono: IconoAlerta },
  { to: "/admin/equipo", label: "Equipo de DataBridge", corto: "Equipo", Icono: IconoEquipo },
  { to: "/admin/accesos", label: "Accesos", corto: "Accesos", Icono: IconoCandado },
  { to: "/admin/reglas", label: "Reglas", corto: "Reglas", Icono: IconoReglas },
];

const PORTALES = {
  debtor: { links: DEUDOR, marca: "DataBridge", sub: "Portal de pago" },
  creditor: { links: EMPRESA, marca: "DataBridge", sub: "Portal de empresas" },
  admin: { links: ADMIN, marca: "DataBridge", sub: "Panel de administración" },
};

/** Las iniciales para el circulo del usuario: "Camila Reyes" -> "CR". */
function iniciales(nombre) {
  return String(nombre || "")
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0].toUpperCase())
    .join("");
}

export default function Layout({ variant }) {
  const { user, logout } = useAuth();
  const esEmpresa = variant === "creditor";
  //  El usuario de una empresa y el admin tienen nombre y correo; el deudor, solo su RUT.
  const conCuenta = variant !== "debtor";
  const { links, marca, sub } = PORTALES[variant] || PORTALES.debtor;
  const [enRiesgo, setEnRiesgo] = useState(0);

  // Cuantos convenios estan atrasados: va como numero al lado del enlace.
  useEffect(() => {
    if (!esEmpresa) return;
    listarEnRiesgo().then((c) => setEnRiesgo(c.length)).catch(() => setEnRiesgo(0));
  }, [esEmpresa]);

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-row">
          <Logo size={40} />
          <div>
            <div className="brand-name">{marca}</div>
            <div className="brand-sub">{sub}</div>
          </div>
        </div>
        {links.map(({ to, label, end, Icono, aparte, riesgo }) => (
          <div key={to}>
            {aparte ? <div className="nav-separador" /> : null}
            <NavLink to={to} end={end} className={({ isActive }) => `nav-link${isActive ? " active" : ""}`}>
              <Icono />
              {label}
              {riesgo && enRiesgo > 0 ? <span className="cuenta" aria-label={`${enRiesgo} en riesgo`}>{enRiesgo}</span> : null}
            </NavLink>
          </div>
        ))}
        <div className="sidebar-foot">
          <TemaToggle conTexto />
          <div className="user-chip">
            <span className="avatar">{conCuenta ? iniciales(user?.nombre) : "TB"}</span>
            <div>
              {conCuenta ? (
                <>
                  <b>{user?.nombre}</b>
                  <span>{user?.correo}</span>
                </>
              ) : (
                <>
                  <b>RUT {rutLegible(user?.rut)}</b>
                  <span>Entraste con tu código</span>
                </>
              )}
            </div>
          </div>
          <button className="btn btn-ghost btn-block" onClick={logout}>
            <IconoSalir />
            Cerrar sesión
          </button>
        </div>
      </aside>

      <div>
        <header className="movil-cabecera">
          <Logo size={32} />
          <span className="brand-name">{marca}</span>
          <TemaToggle />
          <button type="button" className="btn btn-ghost" onClick={logout} aria-label="Cerrar sesión">
            <IconoSalir />
          </button>
        </header>
        <main className="main">
          <Outlet />
        </main>
      </div>

      {/* El asistente responde sobre las deudas de quien pregunta: es solo del deudor. */}
      {variant === "debtor" ? <Chatbot /> : null}
      <nav className="mobile-bar">
        {links.map(({ to, corto, end, Icono }) => (
          <NavLink key={to} to={to} end={end} className={({ isActive }) => (isActive ? "active" : "")}>
            <Icono size={20} />
            {corto}
          </NavLink>
        ))}
      </nav>
    </div>
  );
}
