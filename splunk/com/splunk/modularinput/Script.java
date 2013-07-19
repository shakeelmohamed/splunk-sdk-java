package com.splunk.modularinput;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.print.attribute.standard.Severity;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.lang.annotation.Documented;
import java.nio.*;

/**
 * Script is an abstract base class for implementing modular inputs. Subclasses
 * should override getScheme and streamEvents, and optional configureValidator if the modular
 * input is using external validation.
 */
public abstract class Script {
    /**
     * run encodes all the common behavior of modular inputs. Users should have no reason
     * to override this method in most cases.
     *
     * @param args An array of command line arguments passed to this script.
     * @return an integer to be used as the exit value of this program.
     */
    public int run(String[] args) {
        EventWriter eventWriter = null;
        try {
            eventWriter = new EventWriter();
            return run(args, eventWriter, System.in);
        } catch (XMLStreamException e) {

            System.err.print(stackTraceToLogEntry(
                    Severity.ERROR.toString(),
                    e.getStackTrace())
            );
            return 1;
        }
    }

    public int run(String[] args, EventWriter eventWriter, InputStream in) {
        try {
            if (args.length == 0) {
                // This script is running as an input. Input definitions will be passed on stdin as XML, and
                // the script will write events on stdout and log entries on stderr.
                InputDefinition inputDefinition = InputDefinition.parseDefinition(in);
                streamEvents(inputDefinition, eventWriter);
                eventWriter.close();
                return 0;
            } else if (args[0].toLowerCase().equals("--scheme")) {
                // Splunk has requested XML specifying the scheme for this modular input. Return it and exit.
                Scheme scheme = getScheme();
                if (scheme == null) {
                    eventWriter.log("FATAL", "Modular input script returned a null scheme.");
                    return 1;
                } else {
                    eventWriter.writeXmlDocument(scheme.toXml());
                    return 0;
                }
            } else if (args[0].toLowerCase().equals("--validate-arguments")) {
                NonblockingInputStream stream = new NonblockingInputStream(in);
                ValidationDefinition validationDefinition = ValidationDefinition.parseDefinition(stream);

                try {
                    validateInput(validationDefinition);
                    return 0;
                } catch (Exception e) {
                    DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
                    documentBuilderFactory.setIgnoringElementContentWhitespace(true);
                    DocumentBuilder documentBuilder = null;
                    documentBuilder = documentBuilderFactory.newDocumentBuilder();

                    Document document = documentBuilder.newDocument();

                    Node error = document.createElement("error");
                    document.appendChild(error);

                    Node message = document.createElement("message");
                    error.appendChild(message);

                    Node text = document.createTextNode(e.getLocalizedMessage());
                    message.appendChild(text);

                    eventWriter.writeXmlDocument(document);

                    return 1;
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("ERROR Invalid arguments to modular input script:");
            for (String arg : args) {
                sb.append(" ");
                sb.append(arg);
            }
            System.err.println(sb.toString());
            return 1;
        } catch (Exception e) {
            return logException(e);
        }

    }

    public String stackTraceToLogEntry(String severity, StackTraceElement[] stackTrace) {
        StringBuilder sb = new StringBuilder();
        sb.append(severity);
        sb.append(" ");
        for (StackTraceElement s : stackTrace) {
            sb.append(s.toString());
            sb.append("\\");
        }
        return sb.toString();
    }

    public int logException(Throwable e) {
        System.err.println(stackTraceToLogEntry(EventWriter.ERROR, e.getStackTrace()));
        return 1;
    }

    /**
     * The scheme defines the parameters understood by this modular input.
     *
     * @return a Scheme object representing the parameters for this modular input.
     */
    public abstract Scheme getScheme();

    /**
     * validateInput handles external validation for modular input kinds. When Splunk
     * called a modular input script in validation mode, it will pass in an XML document
     * giving information about the Splunk instance (so you can call back into it if needed)
     * and the name and parameters of the proposed input.
     *
     * If this function does not throw an exception, the validation is assumed to succeed. Otherwise
     * any error throws will be turned into a string and logged back to Splunk.
     *
     * The default implementation always passes.
     *
     * @param definition The parameters for the proposed input passed by splunkd.
     */
    public void validateInput(ValidationDefinition definition) throws Exception {}

    /**
     * The method called to stream events into Splunk. It should do all of its output via
     * EventWriter rather than assuming that there is a console attached.
     *
     * @param ew An object with methods to write events and log messages to Splunk.
     */
    public abstract void streamEvents(InputDefinition inputs, EventWriter ew)
            throws MalformedDataException, XMLStreamException, IOException;
}