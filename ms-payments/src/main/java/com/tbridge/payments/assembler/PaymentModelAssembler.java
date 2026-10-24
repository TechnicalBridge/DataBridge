package com.tbridge.payments.assembler;

import com.tbridge.payments.controller.PaymentController;
import com.tbridge.payments.dto.response.HistoriaResponse;
import com.tbridge.payments.dto.response.PaymentResponse;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.hateoas.server.mvc.BasicLinkBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Los enlaces de un pago.
 *
 * <ul>
 *   <li>{@code self}: el pago.</li>
 *   <li>{@code historia}: el libro, paso por paso.</li>
 *   <li>{@code deuda}: la deuda que paga. Vive en ms-debt, pero la direccion
 *       publica es la misma para todos: el gateway reparte por ruta.</li>
 *   <li>{@code registrar-devolucion}: solo en los pagos para devolver, que ve
 *       la empresa: ahi anota que ya lo devolvio en la pasarela.</li>
 * </ul>
 */
@Component
public class PaymentModelAssembler implements RepresentationModelAssembler<PaymentResponse, EntityModel<PaymentResponse>> {

    @Override
    public EntityModel<PaymentResponse> toModel(PaymentResponse pago) {
        return EntityModel.of(pago,
                linkTo(methodOn(PaymentController.class).one(null, pago.id())).withSelfRel(),
                linkTo(methodOn(PaymentController.class).historia(null, pago.id())).withRel("historia"),
                deuda(pago.debtId()));
    }

    @Override
    public CollectionModel<EntityModel<PaymentResponse>> toCollectionModel(Iterable<? extends PaymentResponse> pagos) {
        return RepresentationModelAssembler.super.toCollectionModel(pagos)
                .add(linkTo(methodOn(PaymentController.class).list(null)).withSelfRel());
    }

    /** Los pagos para devolver de la empresa: cada uno ofrece registrar su devolucion. */
    public CollectionModel<EntityModel<PaymentResponse>> paraDevolver(List<PaymentResponse> pagos) {
        CollectionModel<EntityModel<PaymentResponse>> modelo = toCollectionModel(pagos);
        modelo.getContent().forEach(pago -> pago.add(linkTo(methodOn(PaymentController.class)
                .registrarDevolucion(null, pago.getContent().id(), null)).withRel("registrar-devolucion")));
        return modelo;
    }

    public EntityModel<HistoriaResponse> toModel(Long pagoId, HistoriaResponse historia) {
        return EntityModel.of(historia,
                linkTo(methodOn(PaymentController.class).historia(null, pagoId)).withSelfRel(),
                linkTo(methodOn(PaymentController.class).one(null, pagoId)).withRel("pago"));
    }

    private static Link deuda(Long debtId) {
        return BasicLinkBuilder.linkToCurrentMapping().slash("api").slash("debts").slash(debtId).withRel("deuda");
    }
}
