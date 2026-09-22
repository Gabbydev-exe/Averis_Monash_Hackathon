SELECT
    email_id,
    field_key,
    si_value,
    bl_value,
    si_evidence,
    bl_evidence
FROM shipment_fields
WHERE email_id = 'email_004'
ORDER BY field_key;