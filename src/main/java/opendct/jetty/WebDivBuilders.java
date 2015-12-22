package opendct.jetty;

import opendct.capture.CaptureDevice;
import opendct.channel.ChannelLineup;
import opendct.channel.ChannelManager;
import opendct.sagetv.SageTVManager;
import opendct.sagetv.SageTVPoolManager;

import java.io.PrintWriter;

public class WebDivBuilders {

    public static void getMainPage(PrintWriter out) {
        printHeader(out);
        printLoadedCaptureDeviceTable(out);
        printFooter(out);
    }

    public static void printHeader(PrintWriter out) {
        out.println("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01//EN\" \"http://www.w3.org/TR/html4/strict.dtd\">\n" +
                "<html>\n" +
                "<head>\n" +
                "    <link rel=\"stylesheet\" type=\"text/css\" href=\"css/opendct.css\"/>\n" +
                "    <title>OpenDCT Web Interface</title>\n" +
                "</head>\n" +
                "<body>\n" +
                "<h1>OpenDCT Web Interface</h1>");
    }

    public static void printFooter(PrintWriter out) {
        out.println("</body>");
    }

    public static void printLoadedCaptureDeviceTable(PrintWriter out) {
        out.println("<div class=\"content\"><table class=\"loaded_devices\">");

        for (CaptureDevice captureDevice : SageTVManager.getAllLoadedCaptureDevicesSorted()) {
            printCaptureDeviceCell(captureDevice.getEncoderName(), out);
        }
        out.println("</table></div>");
        out.println("<hr/>");
    }

    public static void printCaptureDeviceCell(String captureDeviceName, PrintWriter out) {
        CaptureDevice captureDevice = SageTVManager.getSageTVCaptureDevice(captureDeviceName, false);

        if (captureDevice == null || out == null) {
            return;
        }
        out.println("<table class=\"loaded_device\">");
        out.println("<tr>");

        out.println("<td class=\"title_cell\">");
        out.println(captureDevice.getEncoderName());
        if (captureDevice.isLocked() && captureDevice.getRecordedBytes() > 0) {
            if (SageTVPoolManager.isUsePools()) {
                String virtualTuner = SageTVPoolManager.getPoolCaptureDeviceToVCaptureDevice(captureDeviceName);
                out.println("<br/>Mapped to SageTV as " + virtualTuner);
            } else {
                out.print("<br/>");
            }
            out.print("<br/>Recording from channel ");
            out.print(captureDevice.getLastChannel());
            out.print(" to ");
            out.println(captureDevice.getRecordFilename());
        } else {
            out.print("<br/><br/>Inactive");
        }
        out.println("</td>");

        if (SageTVPoolManager.isUsePools()) {
            out.println("<td class=\"pool_cell\">");

            String poolName = captureDevice.getEncoderPoolName();

            if (poolName != null && !poolName.equals("")) {
                out.print("Pool: ");
                out.print(captureDevice.getEncoderPoolName());
                out.println("<br/>");
                out.print("Merit: ");
                out.print(captureDevice.getMerit());
                out.println("<br/>");
                out.print("Locked: ");
                out.print(captureDevice.isExternalLocked());
                out.println("<br/>");
            }

            out.println("</td>");
        }


        ChannelLineup lineup = ChannelManager.getChannelLineup(captureDevice.getChannelLineup());
        String lineupName;
        if (lineup != null) {
            lineupName = lineup.getFriendlyName();
        } else {
            lineupName = captureDevice.getChannelLineup();
        }

        out.println("<td class=\"details_cell\">");

        out.print("Lineup: ");
        out.print(lineupName);
        out.println("<br/>");
        out.print("Offline Scan Enabled: ");
        out.print(captureDevice.isOfflineScanEnabled());
        out.println("<br/>");
        out.print("Switch Enabled: ");
        out.print(captureDevice.canSwitch());
        out.println("<br/>");

        out.println("</td>");

        out.println("</tr>");
        out.println("</table>");
    }
}
