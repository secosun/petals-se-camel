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
package org.ow2.petals.camel.se;

import java.io.File;
import java.io.StringReader;
import java.net.URL;

import javax.jbi.messaging.MessageExchange;
import javax.xml.namespace.QName;

import org.apache.camel.converter.jaxp.XmlConverter;
import org.apache.commons.io.input.ReaderInputStream;
import org.custommonkey.xmlunit.Diff;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.ow2.easywsdl.wsdl.api.abstractItf.AbsItfOperation;
import org.ow2.petals.component.framework.junit.RequestMessage;
import org.ow2.petals.component.framework.junit.ResponseMessage;
import org.ow2.petals.component.framework.junit.impl.ServiceConfiguration;
import org.ow2.petals.component.framework.junit.impl.ServiceConfiguration.ServiceType;
import org.ow2.petals.component.framework.junit.impl.message.WrappedRequestToProviderMessage;
import org.ow2.petals.component.framework.junit.impl.message.WrappedResponseToConsumerMessage;
import org.ow2.petals.component.framework.junit.rule.ComponentUnderTest;
import org.ow2.petals.component.framework.junit.rule.ServiceConfigurationFactory;
import org.ow2.petals.junit.rules.log.handler.InMemoryLogHandler;

public abstract class AbstractComponentTest extends AbstractTest {

    /**
     * Converters from Camel
     */
    private static final XmlConverter CONVERTER = new XmlConverter();

    protected static final String SE_CAMEL_JBI_NS = "http://petals.ow2.org/components/petals-se-camel/jbi/version-1.0";

    protected static final URL WSDL11 = Thread.currentThread().getContextClassLoader()
            .getResource("tests/service-1.1.wsdl");

    protected static final URL WSDL20 = Thread.currentThread().getContextClassLoader()
            .getResource("tests/service-2.0.wsdl");

    protected static final URL VALID_ROUTES = Thread.currentThread().getContextClassLoader()
            .getResource("tests/routes-valid.xml");

    protected static final URL INVALID_ROUTES = Thread.currentThread().getContextClassLoader()
            .getResource("tests/routes-invalid.xml");

    protected static final String HELLO_NS = "http://petals.ow2.org";

    protected static final QName WRONG_INTERFACE = new QName(HELLO_NS, "WrongInterface");

    protected static final QName WRONG_SERVICE = new QName(HELLO_NS, "WrongInterface");

    protected static final String EXTERNAL_CAMEL_SERVICE_ID = "theConsumesId";

    protected static final String SU_NAME = "su-name";

    protected static final QName HELLO_INTERFACE = new QName(HELLO_NS, "HelloInterface");

    protected static final QName HELLO_SERVICE = new QName(HELLO_NS, "HelloService");

    protected static final QName HELLO_OPERATION = new QName(HELLO_NS, "sayHello");

    protected static final String EXTERNAL_ENDPOINT_NAME = "externalHelloEndpoint";

    protected static final InMemoryLogHandler IN_MEMORY_LOG_HANDLER = new InMemoryLogHandler();

    protected static final ComponentUnderTest COMPONENT_UNDER_TEST = new ComponentUnderTest().addLogHandler(
            IN_MEMORY_LOG_HANDLER.getHandler()).registerExternalServiceProvider(HELLO_SERVICE, EXTERNAL_ENDPOINT_NAME);

    /**
     * We use a class rule (i.e. static) so that the component lives during all the tests, this enables to test also
     * that successive deploy and undeploy do not create problems.
     */
    @ClassRule
    public static final TestRule chain = RuleChain.outerRule(IN_MEMORY_LOG_HANDLER).around(COMPONENT_UNDER_TEST);

    /**
     * All log traces must be cleared before starting a unit test (because the log handler is static and lives during
     * the whole suite of tests)
     */
    @Before
    public void clearLogTraces() {
        IN_MEMORY_LOG_HANDLER.clear();
    }

    /**
     * We undeploy services after each test (because the component is static and lives during the whole suite of tests)
     */
    @After
    public void undeployAllServices() {
        COMPONENT_UNDER_TEST.undeployAllServices();
    }

    protected ServiceConfiguration createTestService(final QName interfaceName, final QName serviceName,
            final String endpointName, final URL wsdl) {
        final ServiceConfiguration provides = new ServiceConfiguration(interfaceName, serviceName, endpointName,
                ServiceType.PROVIDE, wsdl);

        final ServiceConfiguration consumes = createHelloConsumes();

        // TODO we are missing the routes: need to modify the CDK for that

        provides.addServiceConfigurationDependency(consumes);

        return provides;
    }

    protected ServiceConfiguration createHelloConsumes() {
        final ServiceConfiguration consumes = new ServiceConfiguration(HELLO_INTERFACE, HELLO_SERVICE,
                EXTERNAL_ENDPOINT_NAME, ServiceType.CONSUME);
        consumes.setParameter(new QName("http://petals.ow2.org/components/extensions/version-5", "mep"), "InOut");
        consumes.setParameter(new QName("http://petals.ow2.org/components/extensions/version-5", "operation"),
                HELLO_OPERATION.toString());
        consumes.setParameter(new QName(SE_CAMEL_JBI_NS, "service-id"), EXTERNAL_CAMEL_SERVICE_ID);
        return consumes;
    }

    protected void deployHello(final String suName, final URL wsdl, final Class<?> clazz) throws Exception {
        deploy(suName, HELLO_INTERFACE, HELLO_SERVICE, wsdl, clazz);
    }

    protected void deploy(final String suName, final QName interfaceName, final QName serviceName, final URL wsdl,
            final Class<?> clazz) throws Exception {
        COMPONENT_UNDER_TEST.deployService(suName, new ServiceConfigurationFactory() {
            @Override
            public ServiceConfiguration create() {
                final ServiceConfiguration provides = createTestService(interfaceName, serviceName, "autogenerate",
                        wsdl);
                provides.setServicesSectionParameter(new QName(SE_CAMEL_JBI_NS, "java-routes"), clazz.getName());
                return provides;
            }
        });
    }

    protected void deployHello(final String suName, final URL wsdl, final URL routes) throws Exception {
        deploy(suName, HELLO_INTERFACE, HELLO_SERVICE, wsdl, routes);
    }

    protected void deploy(final String suName, final QName interfaceName, final QName serviceName, final URL wsdl,
            final URL routes) throws Exception {
        final String routesFileName = new File(routes.toURI()).getName();
        COMPONENT_UNDER_TEST.deployService(suName, new ServiceConfigurationFactory() {
            @Override
            public ServiceConfiguration create() {
                final ServiceConfiguration provides = createTestService(interfaceName, serviceName, "autogenerate",
                        wsdl);
                provides.setServicesSectionParameter(new QName(SE_CAMEL_JBI_NS, "xml-routes"), routesFileName);
                provides.addResource(routes);
                return provides;
            }
        });
    }

    protected ResponseMessage sendHello(final String suName, @Nullable final String request,
            @Nullable final String expectedRequest, @Nullable final String response,
            @Nullable final String expectedResponse) throws Exception {
        COMPONENT_UNDER_TEST.pushRequestToProvider(new WrappedRequestToProviderMessage(COMPONENT_UNDER_TEST
                .getServiceConfiguration(suName), HELLO_OPERATION, AbsItfOperation.MEPPatternConstants.IN_OUT.value(),
                request == null ? null : new ReaderInputStream(new StringReader(request))));

        final RequestMessage requestMessage = COMPONENT_UNDER_TEST.pollRequestFromConsumer();

        final MessageExchange exchange = requestMessage.getMessageExchange();

        assertEquals(exchange.getInterfaceName(), HELLO_INTERFACE);
        assertEquals(exchange.getService(), HELLO_SERVICE);
        assertEquals(exchange.getOperation(), HELLO_OPERATION);
        assertEquals(exchange.getEndpoint().getEndpointName(), EXTERNAL_ENDPOINT_NAME);

        if (expectedRequest != null) {
            final Diff diff = new Diff(CONVERTER.toDOMSource(requestMessage.getPayload()),
                    CONVERTER.toDOMSource(expectedRequest));
            assertTrue(diff.similar());
        }

        COMPONENT_UNDER_TEST.pushResponseToConsumer(new WrappedResponseToConsumerMessage(exchange,
                new ReaderInputStream(new StringReader(response))));

        final ResponseMessage responseMessage = COMPONENT_UNDER_TEST.pollResponseFromProvider();

        if (expectedResponse != null) {
            final Diff diff = new Diff(CONVERTER.toDOMSource(responseMessage.getPayload()),
                    CONVERTER.toDOMSource(expectedResponse));
            assertTrue(diff.similar());
        }

        return responseMessage;
    }

}
