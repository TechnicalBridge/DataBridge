import { useEffect, useRef, useState } from "react";
import { NavLink, Outlet, useLocation } from "react-router-dom";
import { listarEnRiesgo } from "../api/deudas";
import { rutLegible } from "../utils/formato";
import { useAuth } from "../store/authStore";
import { useAsistente } from "../store/asistenteStore";
import Logo from "./Logo";
import Chatbot, { BotonAsistente } from "./Chatbot";
import TemaToggle from "./TemaToggle";
import {
  IconoAlerta, IconoAyuda, IconoBitacora, IconoCalendario, IconoChat, IconoCandado, IconoCartera, IconoDocumento,
  IconoEmpresa, IconoEquipo, IconoMas, IconoMegafono, IconoPanel, IconoRecibo, IconoReglas, IconoSalir, IconoSubir,
  IconoUsuario,
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
  { to: "/admin/deudores", label: "Deudores", corto: "Deudores", Icono: IconoUsuario },
  { to: "/admin/pagos", label: "Pagos", corto: "Pagos", Icono: IconoRecibo },
  { to: "/admin/reclamos", label: "Reclamos", corto: "Reclamos", Icono: IconoChat },
  { to: "/admin/operacion", label: "Operación", corto: "Operación", Icono: IconoAlerta },
  { to: "/admin/equipo", label: "Equipo de DataBridge", corto: "Equipo", Icono: IconoEquipo },
  { to: "/admin/accesos", label: "Accesos", corto: "Accesos", Icono: IconoCandado },
  { to: "/admin/bitacora", label: "Bitácora", corto: "Bitácora", Icono: IconoBitacora },
  { to: "/admin/reglas", label: "Reglas", corto: "Reglas", Icono: IconoReglas },
];

const PORTALES = {
  debtor: { links: DEUDOR, marca: "DataBridge", sub: "Portal de pago" },
  creditor: { links: EMPRESA, marca: "DataBridge", sub: "Portal de empresas" },
  //  El panel tiene mas entradas de las que caben en la barra del telefono: van 4 y "Mas" (#436).
  admin: { links: ADMIN, marca: "DataBridge", sub: "Panel de administración", enLaBarra: 4 },
};

/**
 * La barra de abajo en el telefono. Si el portal tiene mas entradas de las que
 * caben, muestra las primeras y "Mas", que abre una lista con el resto: se
 * cierra al elegir, con Esc o tocando afuera, y el foco vuelve al boton.
 */
function BarraDelTelefono({ links, enLaBarra }) {
  const sobran = enLaBarra && links.length > enLaBarra + 1 ? links.slice(enLaBarra) : [];
  const visibles = sobran.length ? links.slice(0, enLaBarra) : links;
  const [abierto, setAbierto] = useState(false);
  const { pathname } = useLocation();
  const boton = useRef(null);
  const lista = useRef(null);
  //  Si la pagina abierta es una de las de "Mas", el boton se marca: asi se sabe donde se esta.
  const aqui = sobran.some(({ to }) => pathname === to || pathname.startsWith(`${to}/`));

  useEffect(() => { setAbierto(false); }, [pathname]);

  useEffect(() => {
    if (!abierto) return undefined;
    lista.current?.querySelector("a")?.focus({ preventScroll: true });
    const alTeclado = (e) => {
      if (e.key !== "Escape") return;
      setAbierto(false);
      boton.current?.focus({ preventScroll: true });
    };
    document.addEventListener("keydown", alTeclado);
    return () => document.removeEventListener("keydown", alTeclado);
  }, [abierto]);

  function cerrar() {
    setAbierto(false);
    boton.current?.focus({ preventScroll: true });
  }

  return (
    <>
      {abierto ? (
        <>
          <div className="mas-fondo" onClick={cerrar} aria-hidden="true" />
          <nav className="mas-lista" id="mas-lista" ref={lista} aria-label="Más secciones">
            {sobran.map(({ to, label, end, Icono }) => (
              <NavLink key={to} to={to} end={end} onClick={() => setAbierto(false)}
                       className={({ isActive }) => (isActive ? "active" : "")}>
                <Icono size={20} />
                {label}
              </NavLink>
            ))}
          </nav>
        </>
      ) : null}
      <nav className="mobile-bar">
        {visibles.map(({ to, corto, end, Icono }) => (
          <NavLink key={to} to={to} end={end} className={({ isActive }) => (isActive ? "active" : "")}>
            <Icono size={20} />
            {corto}
          </NavLink>
        ))}
        {sobran.length ? (
          <button type="button" ref={boton} className={aqui || abierto ? "active" : ""} aria-expanded={abierto}
                  aria-controls="mas-lista" onClick={() => (abierto ? cerrar() : setAbierto(true))}>
            <IconoMas size={20} />
            Más
          </button>
        ) : null}
      </nav>
    </>
  );
}

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
  const { links, marca, sub, enLaBarra } = PORTALES[variant] || PORTALES.debtor;
  const [enRiesgo, setEnRiesgo] = useState(0);
  //  En el computador, el asistente abierto toma una columna y la pagina se angosta: no tapa nada (#303).
  const conAsistente = useAsistente((s) => s.abierto) && variant === "debtor";

  // Cuantos convenios estan atrasados: va como numero al lado del enlace.
  useEffect(() => {
    if (!esEmpresa) return;
    listarEnRiesgo().then((c) => setEnRiesgo(c.length)).catch(() => setEnRiesgo(0));
  }, [esEmpresa]);

  return (
    <div className={`app-shell${conAsistente ? " con-asistente" : ""}`}>
      <aside className="sidebar">
        <div className="brand-row">
          <Logo size={40} />
          <div>
            <div className="brand-name">{marca}</div>
            <div className="brand-sub">{sub}</div>
          </div>
        </div>
        {/* El asistente se abre desde arriba, no con una burbuja que tape la pagina (#303). */}
        {variant === "debtor" ? <BotonAsistente conTexto /> : null}
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
            <span className="avatar">{conCuenta ? iniciales(user?.nombre) : <IconoUsuario size={18} />}</span>
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
          {variant === "debtor" ? <BotonAsistente /> : null}
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
      <BarraDelTelefono links={links} enLaBarra={enLaBarra} />
    </div>
  );
}
