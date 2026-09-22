SELECT x.email_id, x.category, a.evaluation_status
FROM email_extractions x
         JOIN email_assessments a
              ON a.email_id = x.email_id
                  AND a.revision = x.revision
LIMIT 1;