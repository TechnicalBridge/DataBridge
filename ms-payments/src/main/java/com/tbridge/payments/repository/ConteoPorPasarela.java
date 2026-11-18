package com.tbridge.payments.repository;

import com.tbridge.payments.model.Payment;

public record ConteoPorPasarela(Payment.Gateway pasarela, long pagos) {
}
