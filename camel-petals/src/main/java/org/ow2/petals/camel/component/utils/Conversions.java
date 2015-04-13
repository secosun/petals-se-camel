/**
 * Copyright (c) 2015 Linagora
 * 
 * This program/library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 2.1 of the License, or (at your
 * option) any later version.
 * 
 * This program/library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program/library; If not, see <http://www.gnu.org/licenses/>
 * for the GNU Lesser General Public License version 2.1.
 */
package org.ow2.petals.camel.component.utils;

import java.util.Map.Entry;
import java.util.Set;

import javax.activation.DataHandler;
import javax.jbi.messaging.Fault;
import javax.jbi.messaging.MessagingException;
import javax.jbi.messaging.NormalizedMessage;
import javax.xml.transform.Source;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Message;

import com.ebmwebsourcing.easycommons.lang.ExceptionHelper;

/**
 * Utils to convert between petals exchange and camel exchange.
 * 
 * Important: Some of this code makes the assumption that an inoptionalout camel exchange MUST have an out message, even
 * though camel allows for modifying inline the message. This is needed because we can't know if an inoutoptional has no
 * out because it's in is modified or because the exchange is finished!
 * 
 * @author vnoel
 *
 */
public class Conversions {

    private Conversions() {
    }

    /**
     * To populate a new camel exchange with an exchange coming from petals
     * 
     * TODO should we have a set of "normalized" headers such as jbi.operation or things like that?
     * 
     * @param camelExchange
     * @param exchange
     */
    public static void populateNewCamelExchange(final Exchange camelExchange,
            final org.ow2.petals.component.framework.api.message.Exchange exchange) {

        camelExchange.setExchangeId(exchange.getExchangeId());

        // normally the MEP should be already correctly set...
        // TODO check for compatibility and use the mep of the exchange?

        for (String prop : exchange.getPropertyNames()) {
            camelExchange.setProperty(prop, exchange.getProperty(prop));
        }

        populateCamelMessage(camelExchange.getIn(), exchange.getInMessage());
    }

    /**
     * To populate a camel exchange from the answer we got through petals
     * 
     * @param camelExchange
     * @param exchange
     */
    public static void populateAnswerCamelExchange(final Exchange camelExchange,
            final org.ow2.petals.component.framework.api.message.Exchange exchange) {

        // TODO should I update properties?!

        // TODO add checks w.r.t. MEP?

        if (exchange.isErrorStatus()) {
            // there has been a technical error
            camelExchange.setException(exchange.getError());
        } else if (exchange.getFault() != null) {
            // there has been a fault
            populateCamelMessage(camelExchange.getOut(), exchange.getFault());
            camelExchange.getOut().setFault(true);
        } else if (exchange.isOutMessage()) {
            // this is a response
            populateCamelMessage(camelExchange.getOut(), exchange.getOutMessage());
        } else {
            // the exchange is finished! it corresponds to done for petals exchange, but in Camel there is
            // nothing
            // specific to do...
        }
    }

    private static void populateCamelMessage(final Message camelMessage, final NormalizedMessage message) {

        // Normally, it is an empty message that is populated...

        @SuppressWarnings("unchecked")
        final Set<String> props = message.getPropertyNames();
        for (String prop : props) {
            camelMessage.setHeader(prop, message.getProperty(prop));
        }

        @SuppressWarnings("unchecked")
        final Set<String> attachs = message.getAttachmentNames();
        for (String attach : attachs) {
            camelMessage.addAttachment(attach, message.getAttachment(attach));
        }

        camelMessage.setBody(message.getContent());
    }

    public static void populateNewPetalsExchange(
            final org.ow2.petals.component.framework.api.message.Exchange exchange, final Exchange camelExchange)
            throws MessagingException {

        // MEP is already set (TODO check coherence with the one of the camel exchange?!)

        for (final Entry<String, Object> e : camelExchange.getProperties().entrySet()) {
            exchange.setProperty(e.getKey(), e.getValue());
        }

        Conversions.populateNormalizedMessage(exchange.getInMessage(), camelExchange.getIn());
    }

    public static void populateAnswerPetalsExchange(
            final org.ow2.petals.component.framework.api.message.Exchange exchange, final Exchange camelExchange)
            throws MessagingException {

        // TODO should I update properties?!

        // Note: the Petals exchange checks that all is correct w.r.t. to MEP and status

        if (camelExchange.hasOut() && camelExchange.getOut().isFault()) {
            final Fault fault = exchange.createFault();
            Conversions.populateNormalizedMessage(fault, camelExchange.getOut());
            exchange.setFault(fault);
        } else if (camelExchange.getException() != null) {
            convertExceptionToError(exchange, camelExchange);
        } else {
            final ExchangePattern mep = camelExchange.getPattern();

            if (mep == ExchangePattern.InOut) {
                final Message out;
                // sometimes camel exchange out is stored inplace of the in by Camel processors...
                if (!camelExchange.hasOut()) {
                    out = camelExchange.getIn();
                } else {
                    out = camelExchange.getOut();
                }
                Conversions.populateNormalizedMessage(exchange.getOutMessage(), out);
            } else if (mep == ExchangePattern.InOptionalOut) {
                if (camelExchange.hasOut()) {
                    Conversions.populateNormalizedMessage(exchange.getOutMessage(), camelExchange.getOut());
                } else {
                    // the exchange is finished
                    exchange.setDoneStatus();
                    // NOTE: we make the assumption that in the case of an optional out, the out message of
                    // the camel
                    // exchange is populated (sometimes the in message is used instead of the out in Camel
                    // processors...
                    // this is an ambiguity we can only handle by choosing this rule)
                    // TODO add documentation
                }
            } else {
                exchange.setDoneStatus();
            }
        }
    }

    private static void convertExceptionToError(final org.ow2.petals.component.framework.api.message.Exchange exchange,
            final Exchange camelExchange) {
        final Exception exception = camelExchange.getException();
        final String exceptionName = exception.getClass().getName();
        // TODO is that correct?
        if (exceptionName.startsWith("java.") || exceptionName.startsWith("javax.")
                || exceptionName.startsWith("org.w3c.") || exceptionName.startsWith("org.xml.")) {
            exchange.setError(exception);
        } else {
            exchange.setError(new MessagingException(ExceptionHelper.getStackTrace(exception)));
        }
    }

    private static void populateNormalizedMessage(final NormalizedMessage message, final Message camelMessage)
            throws MessagingException {

        // Normally, it is an empty message that is populated...

        for (final Entry<String, Object> e : camelMessage.getHeaders().entrySet()) {
            message.setProperty(e.getKey(), e.getValue());
        }

        for (final Entry<String, DataHandler> e : camelMessage.getAttachments().entrySet()) {
            message.addAttachment(e.getKey(), e.getValue());
        }

        // let's use available converters (see http://camel.apache.org/type-converter.html) to get the
        // body as a Source
        // TODO make sure this actually work
        message.setContent(camelMessage.getBody(Source.class));
    }
}
