/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne_systems.model;

/**
 * Min/avg/max pickup-time hours for a single category.
 * <p>
 * Kept label-free on purpose: the category label is the map key in {@link ReportData#pickupTimeStats}, mirroring how
 * {@code Map<String, Double>} worked before. The JSON API adds the label via
 * {@code StatMetricPoint(label, min, avg, max)}.
 */
public record PickupTimeStat(Double min, Double avg, Double max) {
}
