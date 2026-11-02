import { client, embebidos, pedir, publico } from "./client";
import { descargar } from "../utils/exportar";

/**
 * Abrir el cobro. No lleva monto: ms-payments se lo pregunta a ms-debt. Si lo
 * mandara el navegador, bastaria editar la peticion para pagar un peso.
 *
 * Sin cuotas (null) se cobra todo el saldo; con cuotas, solo esas, que tienen
 * que ser las que vencen primero.
 */
export const abrirCobro = (debtId, installmentIds, pasarela) =>
  pedir("/payments/checkout", { method: "POST", body: { debtId, installmentIds, gateway: pasarela } });

export const obtenerPago = (id) => pedir(`/payments/${id}`);

/**
 * Los pagos ya abonados: al deudor, los suyos; a la empresa, los de su
 * cartera. Salen de ms-debt, donde vive la cuenta de cada deuda.
 */
export const listarPagos = () => pedir("/debts/pagos").then((data) => embebidos(data, "pagos"));

/**
 * Los pagos duplicados de la cartera: el deudor pagó dos veces las mismas
 * cuotas y el segundo no se abonó. Vienen con la referencia de la pasarela y
 * el RUT del deudor, para devolverlos.
 */
export const listarParaDevolver = () => pedir("/payments/para-devolver").then((data) => embebidos(data, "payments"));

/**
 * Anota que la empresa ya devolvió un pago duplicado en la pasarela, con la
 * referencia que le dio. DataBridge no mueve plata: solo deja constancia.
 */
export const registrarDevolucion = (id, referencia) =>
  pedir(`/payments/${id}/devolucion`, { method: "POST", body: { referencia } });

/**
 * Los pagos ya devueltos, con cuándo y con qué referencia: a la empresa, los de
 * su cartera; al deudor, los suyos.
 */
export const listarDevueltos = () => pedir("/payments/devueltos").then((data) => embebidos(data, "payments"));

/** El comprobante de un pago, descargado como archivo. */
export async function descargarComprobante(id) {
  const res = await client.get(`/debts/pagos/${id}/comprobante`, { responseType: "blob" });
  descargar(res.data, `comprobante-pago-${id}.pdf`);
}

/** La pasarela simulada no tiene sesion: la protege la firma del enlace. */
export const pagoPublico = (id, sig) => publico.get(`/payments/public/${id}`, { params: { sig } }).then((r) => r.data);

export const confirmarPagoPublico = (id, sig) =>
  publico.post(`/payments/public/${id}/confirm`, null, { params: { sig } }).then((r) => r.data);

/** Con Khipu de verdad: que ms-payments le pregunte a Khipu en que quedo el pago. */
export const verificarPagoPublico = (id, sig) =>
  publico.post(`/payments/public/${id}/verificar`, null, { params: { sig } }).then((r) => r.data);

/** El deudor se arrepintio en Khipu: queda fallido, salvo que haya alcanzado a pagar. */
export const cancelarPagoPublico = (id, sig) =>
  publico.post(`/payments/public/${id}/cancelar`, null, { params: { sig } }).then((r) => r.data);
