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
package org.ow2.petals.camel.se.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;

import org.apache.commons.lang.StringUtils;
import org.ow2.easywsdl.schema.api.XmlException;
import org.ow2.easywsdl.wsdl.WSDLFactory;
import org.ow2.easywsdl.wsdl.api.Binding;
import org.ow2.easywsdl.wsdl.api.BindingOperation;
import org.ow2.easywsdl.wsdl.api.Description;
import org.ow2.easywsdl.wsdl.api.WSDLReader;
import org.ow2.easywsdl.wsdl.api.abstractItf.AbsItfOperation.MEPPatternConstants;
import org.ow2.petals.camel.ServiceEndpointOperation;
import org.ow2.petals.camel.se.CamelSE;
import org.ow2.petals.camel.se.PetalsCamelSender;
import org.ow2.petals.camel.se.exceptions.InvalidJBIConfigurationException;
import org.ow2.petals.camel.se.impl.ServiceEndpointOperationConsumes;
import org.ow2.petals.camel.se.impl.ServiceEndpointOperationProvides;
import org.ow2.petals.component.framework.api.configuration.ConfigurationExtensions;
import org.ow2.petals.component.framework.jbidescriptor.generated.Consumes;
import org.ow2.petals.component.framework.jbidescriptor.generated.Jbi;
import org.ow2.petals.component.framework.jbidescriptor.generated.Provides;
import org.ow2.petals.component.framework.jbidescriptor.generated.Services;
import org.ow2.petals.component.framework.su.ServiceUnitDataHandler;
import org.ow2.petals.component.framework.util.ServiceEndpointKey;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.google.common.collect.HashBiMap;
import com.google.common.collect.Lists;

/**
 * 
 * @author vnoel
 *
 */
public class PetalsCamelJBIHelper {

    private static final String SERVICE_ID_PROPERTY = "service-id";

    private static final String PETALS_CAMEL_JBI_NS_URI = "http://petals.ow2.org/components/petals-se-camel/jbi/version-1.0";

    private static final QName PETALS_CAMEL_JBI_ROUTE_CLASS = new QName(PETALS_CAMEL_JBI_NS_URI, "java-routes");

    private static final QName PETALS_CAMEL_JBI_ROUTE_XML = new QName(PETALS_CAMEL_JBI_NS_URI, "xml-routes");

    private static final String PETALS_CAMEL_WSDL_NS_URI = "http://petals.ow2.org/components/petals-se-camel/wsdl/version-1.0";

    private static final QName PETALS_CAMEL_WSDL_OPERATION = new QName(PETALS_CAMEL_WSDL_NS_URI, "operation");

    private static final String PETALS_CAMEL_WSDL_OPERATION_SERVICEID = "service-id";

    private PetalsCamelJBIHelper() {
    }

    /**
     * returns a Map of service-id <-> service/endpoint/operation
     * 
     * @param suDH
     * @param jbiListener
     * @return
     * @throws InvalidJBIConfigurationException
     */
    public static Map<String, ServiceEndpointOperation> extractServicesIdAndEndpointOperations(
            final ServiceUnitDataHandler suDH, final CamelSE component)
            throws InvalidJBIConfigurationException {

        final Jbi jbiDescriptor = suDH.getDescriptor();

        // let's use a bimap to accelerate checking of containsValue()
        final Map<String, ServiceEndpointOperation> sid2seo = HashBiMap.create();


        // for provides, there is one serviceId per operation of each provides
        for (final Provides p : jbiDescriptor.getServices().getProvides()) {

            final ServiceEndpointKey key = new ServiceEndpointKey(p);

            final Document wsdlDoc = suDH.getEpServiceDesc().get(key);

            final List<OperationData> seos;
            try {
                seos = getOperationsAndServiceId(wsdlDoc, p.getInterfaceName());
            } catch (final URISyntaxException | XmlException e) {
                throw new InvalidJBIConfigurationException("Exception while parsing WSDL", e);
            }

            for (final OperationData od : seos) {
                if (sid2seo.containsKey(od.serviceId)) {
                    throw new InvalidJBIConfigurationException("Duplicate " + SERVICE_ID_PROPERTY + " (" + od.serviceId
                            + ") in the operation " + od.operation);
                }
                final ServiceEndpointOperation seo = new ServiceEndpointOperationProvides(od.operation, od.mep,
                        new PetalsCamelSender(component, p));
                if (sid2seo.containsValue(seo)) {
                    throw new InvalidJBIConfigurationException("Duplicate service " + seo);
                }
                sid2seo.put(od.serviceId, seo);
            }
        }

        // for consumes, there is one serviceId per consumes (because it includes the operation)
        for (final Consumes c : jbiDescriptor.getServices().getConsumes()) {

            final ServiceEndpointOperation seo = new ServiceEndpointOperationConsumes(new PetalsCamelSender(component,
                    c));

            final String serviceId = getServiceId(c, suDH);

            if (sid2seo.containsKey(serviceId)) {
                throw new InvalidJBIConfigurationException("Duplicate " + SERVICE_ID_PROPERTY + " (" + serviceId
                        + ") in the consumes " + c.getServiceName());
            }

            sid2seo.put(serviceId, seo);
        }

        return sid2seo;
    }

    public static void populateRouteLists(final Services servicesNode, final List<String> classNames,
            final List<String> xmlNames) {
        for (final Element e : servicesNode.getAnyOrAny()) {
            if (hasQName(e, PETALS_CAMEL_JBI_ROUTE_CLASS)) {
                classNames.add(e.getTextContent());
            } else if (hasQName(e, PETALS_CAMEL_JBI_ROUTE_XML)) {
                xmlNames.add(e.getTextContent());
            }
        }
    }

    public static List<OperationData> getOperationsAndServiceId(final Document doc,
            final QName interfaceName) throws URISyntaxException, XmlException, InvalidJBIConfigurationException {

        final List<OperationData> results = Lists.newArrayList();

        final WSDLReader reader = WSDLFactory.newInstance().newWSDLReader();

        final Description desc = reader.read(doc);

        for (final Binding binding : desc.getBindings()) {
            if (!interfaceName.equals(binding.getInterface().getQName())) {
                // let's skip it, it's not the one we are looking for
                continue;
            }

            for (final BindingOperation operation : binding.getBindingOperations()) {
                final QName qName = operation.getQName();
                final MEPPatternConstants mep = binding.getInterface().getOperation(qName).getPattern();

                Element camelOperation = null;
                for (final Element e : operation.getOtherElements()) {
                    if (camelOperation != null) {
                        throw new InvalidJBIConfigurationException("Duplicate " + PETALS_CAMEL_WSDL_OPERATION
                                + " available for the operation " + qName);
                    }
                    if (hasQName(e, PETALS_CAMEL_WSDL_OPERATION)) {
                        camelOperation = e;
                    }
                }
                if (camelOperation == null) {
                    throw new InvalidJBIConfigurationException("No " + PETALS_CAMEL_WSDL_OPERATION
                            + " available for the operation " + qName);
                }

                final String serviceId = camelOperation.getAttribute(PETALS_CAMEL_WSDL_OPERATION_SERVICEID);

                if (serviceId == null || StringUtils.isEmpty(serviceId)) {
                    throw new InvalidJBIConfigurationException("No " + PETALS_CAMEL_WSDL_OPERATION_SERVICEID
                            + " attribute for the operation " + qName);
                }
                results.add(new OperationData(qName, mep.value(), serviceId));
            }
        }
        return results;
    }

    public static boolean hasQName(final Node e, final QName name) {
        return new QName(e.getNamespaceURI(), e.getLocalName()).equals(name);
    }

    public static String getServiceId(final Consumes s, final ServiceUnitDataHandler suDH)
            throws InvalidJBIConfigurationException {

        final ConfigurationExtensions extensions = suDH.getConfigurationExtensions(s);
        final String serviceId = extensions.get(SERVICE_ID_PROPERTY);

        if (serviceId == null || serviceId.isEmpty()) {
            throw new InvalidJBIConfigurationException("No " + SERVICE_ID_PROPERTY + " defined for the consumes "
                    + s.getServiceName());
        }

        return serviceId;
    }

    @SuppressWarnings("all")
    public static class OperationData {

        public final QName operation;

        public final URI mep;

        public final String serviceId;

        public OperationData(final QName operation, final URI mep, final String serviceId) {
            this.operation = operation;
            this.mep = mep;
            this.serviceId = serviceId;
        }
    }
}
